package com.jmr.jdms.migration;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Command-line tool that migrates legacy JDMS documents and metadata into JDMS Next (backlog item B-12, ADR-0020).
 *
 * <p>Commands: {@code run} and {@code catalog} (see {@code MigrationCommand}).
 *
 * <p>The legacy AES secret is used only inside this tool, read from a secret at runtime, and never printed, logged
 * or committed (CLAUDE.md rule 11).
 */
@SpringBootApplication
public class JdmsMigrationApplication {

    /** Configuration file name: {@code migration.yml}, so it never mixes with an application's own settings. */
    public static final String CONFIG_NAME = "migration";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(JdmsMigrationApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", CONFIG_NAME));
        System.exit(SpringApplication.exit(application.run(args)));
    }
}
