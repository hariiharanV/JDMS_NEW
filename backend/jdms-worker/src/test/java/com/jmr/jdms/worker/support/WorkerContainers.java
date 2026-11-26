package com.jmr.jdms.worker.support;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real services the worker uses: PostgreSQL (with the core's schema), S3 (SeaweedFS), ClamAV, Tika with
 * Arabic OCR (built from {@code deploy/tika}) and Gotenberg. Images are pinned in the parent POM.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WorkerContainers {

    /** Matches the 68-byte EICAR test file; clamd then starts in seconds instead of downloading its database. */
    static final String EICAR_SIGNATURE = "44d88612fea8a8f36de82e1278abb02f:68:Eicar-Test-Signature\n";

    /** As in production, the worker connects as the application user; Flyway creates the schema as the owner. */
    @Bean
    AppUserPostgres postgres() {
        return new AppUserPostgres(System.getProperty("test.image.postgres"));
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties(AppUserPostgres postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> AppUserPostgres.APP_USER);
            registry.add("spring.datasource.password", () -> postgres.appPassword);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
            registry.add("spring.flyway.placeholders.app_user", () -> AppUserPostgres.APP_USER);
        };
    }

    /**
     * The schema owner's connection, for fixtures the application user may not write (rows without their parents).
     * Not a {@code DataSource} bean, so the worker never uses it.
     */
    @Bean
    OwnerJdbc ownerJdbc(AppUserPostgres postgres) {
        DriverManagerDataSource owner = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        return new OwnerJdbc(new NamedParameterJdbcTemplate(owner),
                new TransactionTemplate(new DataSourceTransactionManager(owner)));
    }

    public record OwnerJdbc(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
    }

    @Bean
    SeaweedFs seaweedfs() {
        return new SeaweedFs(System.getProperty("test.image.seaweedfs"));
    }

    @Bean
    GenericContainer<?> clamav() {
        return new GenericContainer<>(DockerImageName.parse(System.getProperty("test.image.clamav")))
                .withCopyToContainer(Transferable.of(EICAR_SIGNATURE), "/var/lib/clamav/eicar.hdb")
                .withEnv("CLAMD_CONF_StreamMaxLength", "10M")
                // an argument starting with "-" makes the image run clamd only (no database download)
                .withCommand("--foreground")
                .withExposedPorts(3310)
                .waitingFor(Wait.forLogMessage(".*Self checking every.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Bean
    GenericContainer<?> tika() {
        return new GenericContainer<>(new ImageFromDockerfile("jdms-tika-test", false)
                .withFileFromPath(".", Path.of(System.getProperty("test.tika.dockerfile"))))
                .withExposedPorts(9998)
                .waitingFor(Wait.forHttp("/version").forPort(9998).withStartupTimeout(Duration.ofMinutes(3)));
    }

    @Bean
    GenericContainer<?> gotenberg() {
        return new GenericContainer<>(DockerImageName.parse(System.getProperty("test.image.gotenberg")))
                .withExposedPorts(3000)
                .waitingFor(Wait.forHttp("/health").forPort(3000).withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Bean
    DynamicPropertyRegistrar serviceProperties(SeaweedFs seaweedfs, GenericContainer<?> clamav,
            GenericContainer<?> tika, GenericContainer<?> gotenberg) {
        return registry -> {
            registry.add("jdms.storage.endpoint", seaweedfs::endpoint);
            registry.add("jdms.storage.access-key", () -> seaweedfs.accessKey);
            registry.add("jdms.storage.secret-key", () -> seaweedfs.secretKey);
            registry.add("jdms.worker.clamav.host", clamav::getHost);
            registry.add("jdms.worker.clamav.port", () -> clamav.getMappedPort(3310));
            registry.add("jdms.worker.tika.url", () -> "http://" + tika.getHost() + ":" + tika.getMappedPort(9998));
            registry.add("jdms.worker.gotenberg.url", () -> url(gotenberg));
        };
    }

    public static String url(GenericContainer<?> gotenberg) {
        return "http://" + gotenberg.getHost() + ":" + gotenberg.getMappedPort(3000);
    }

    /** PostgreSQL with the application's login role (ADR-0018), created with a random password on start. */
    public static class AppUserPostgres extends PostgreSQLContainer {

        public static final String APP_USER = "jdms_app";

        final String appPassword = random();

        AppUserPostgres(String image) {
            super(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
        }

        @Override
        protected void containerIsStarted(InspectContainerResponse containerInfo) {
            super.containerIsStarted(containerInfo);
            try (Connection connection = DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
                    Statement statement = connection.createStatement()) {
                // DDL takes no bind parameters; both values are generated here (a constant and random hex)
                statement.execute("create role " + APP_USER + " login password '" + appPassword + "'");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not create the application database user", e);
            }
        }
    }

    /** SeaweedFS with its S3 gateway and random credentials. */
    public static class SeaweedFs extends GenericContainer<SeaweedFs> {

        final String accessKey = random();
        final String secretKey = random();

        SeaweedFs(String image) {
            super(DockerImageName.parse(image));
            withCopyToContainer(Transferable.of("{\"identities\":[{\"name\":\"jdms\",\"credentials\":[{\"accessKey\":\""
                    + accessKey + "\",\"secretKey\":\"" + secretKey + "\"}],\"actions\":[\"Admin\",\"Read\",\"List\","
                    + "\"Tagging\",\"Write\"]}]}"), "/etc/seaweedfs/s3.json");
            withCommand("server", "-dir=/data", "-ip.bind=0.0.0.0", "-s3", "-s3.port=8333",
                    "-s3.config=/etc/seaweedfs/s3.json");
            withExposedPorts(8333);
            waitingFor(Wait.forHttp("/healthz").forPort(8333).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));
        }

        String endpoint() {
            return "http://" + getHost() + ":" + getMappedPort(8333);
        }
    }

    private static String random() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
