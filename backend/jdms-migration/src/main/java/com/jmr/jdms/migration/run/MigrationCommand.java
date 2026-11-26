package com.jmr.jdms.migration.run;

import com.jmr.jdms.migration.config.MigrationProperties;
import com.jmr.jdms.migration.report.Reconciliation;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * {@code run} migrates documents (delta mode with {@code --jdms.migration.since=<instant>}); {@code catalog} proposes
 * missing document types, folders and folder permissions. Exit code 0: done without exceptions; 3: done, with
 * exceptions listed in the report; 2: unknown command; 1: the run failed.
 */
@Component
class MigrationCommand implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(MigrationCommand.class);

    private final ObjectProvider<MigrationRunner> runner;
    private final ObjectProvider<CatalogProposer> catalog;
    private final MigrationProperties properties;
    private int exitCode;

    MigrationCommand(ObjectProvider<MigrationRunner> runner, ObjectProvider<CatalogProposer> catalog,
            MigrationProperties properties) {
        this.runner = runner;
        this.catalog = catalog;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = args.getNonOptionArgs().isEmpty() ? "" : args.getNonOptionArgs().getFirst();
        Reconciliation report;
        switch (command) {
            case "run" -> report = runner.getObject().run();
            case "catalog" -> report = catalog.getObject().run();
            default -> {
                log.info("Usage: jdms-migration run [--jdms.migration.since=<instant>] | catalog");
                exitCode = command.isEmpty() ? 0 : 2;
                return;
            }
        }
        Path written = report.write(properties.reportDir());
        log.info("Reconciliation report written to {}", written);
        exitCode = report.exceptions().isEmpty() ? 0 : 3;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
