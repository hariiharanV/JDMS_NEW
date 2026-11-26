package com.jmr.jdms.perf;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Performance tooling for test environments (backlog item B-13, ADR-0021): seeds the synthetic tenant {@code PERF}
 * and generates synthetic documents in it. Never shipped to a bank; the Gatling load tests live in this module's
 * tests.
 *
 * <p>Commands: {@code seed} and {@code generate} (see {@code PerfCommand}).
 */
@SpringBootApplication
public class JdmsPerfApplication {

    /** Configuration file name: {@code perf.yml}. */
    public static final String CONFIG_NAME = "perf";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(JdmsPerfApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", CONFIG_NAME));
        System.exit(SpringApplication.exit(application.run(args)));
    }
}
