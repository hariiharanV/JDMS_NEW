package com.jmr.jdms;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and S3 (SeaweedFS) for integration tests: no H2, no database or storage mocks. Images are pinned
 * in the parent POM; database and S3 credentials are random per run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** The container's superuser owns the schema and runs Flyway; the application connects as a separate user. */
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
        };
    }

    @Bean
    OwnerJdbc ownerJdbc(AppUserPostgres postgres) {
        return new OwnerJdbc(new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())));
    }

    /**
     * The schema owner's connection, for tests that must do what the application user cannot: tamper with audit rows
     * or switch triggers off. Never a {@code JdbcTemplate} bean, so it cannot be injected by accident.
     */
    public record OwnerJdbc(JdbcTemplate jdbc) {
    }

    @Bean
    SeaweedFsContainer seaweedfs() {
        return new SeaweedFsContainer(System.getProperty("test.image.seaweedfs"));
    }

    /** OpenSearch without the security plugin, as in deploy/docker-compose.yml. */
    @Bean
    GenericContainer<?> opensearch() {
        return new GenericContainer<>(DockerImageName.parse(System.getProperty("test.image.opensearch")))
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    @Bean
    DynamicPropertyRegistrar searchProperties(GenericContainer<?> opensearch) {
        return registry -> registry.add("jdms.search.url", () -> "http://" + opensearch.getHost() + ":"
                + opensearch.getMappedPort(9200));
    }

    @Bean
    DynamicPropertyRegistrar s3Properties(SeaweedFsContainer seaweedfs) {
        return registry -> {
            registry.add("jdms.storage.endpoint", seaweedfs::endpoint);
            registry.add("jdms.storage.access-key", () -> seaweedfs.accessKey);
            registry.add("jdms.storage.secret-key", () -> seaweedfs.secretKey);
        };
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

    /** SeaweedFS with its S3 gateway; the identity file is generated with random credentials. */
    public static class SeaweedFsContainer extends GenericContainer<SeaweedFsContainer> {

        final String accessKey = random();
        final String secretKey = random();

        SeaweedFsContainer(String image) {
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
