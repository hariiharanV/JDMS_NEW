package com.jmr.jdms.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Without a command the tool only prints its usage: nothing is configured, read or decrypted. */
@SpringBootTest(properties = "spring.config.name=" + JdmsMigrationApplication.CONFIG_NAME)
class MigrationApplicationTest {

    @Autowired Environment environment;
    @Autowired ExitCodeGenerator command;

    @Test
    void startsAsANonWebCommandLineApplication() {
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(environment.getProperty("spring.datasource.hikari.read-only")).isEqualTo("true");
        assertThat(command.getExitCode()).isZero();
    }
}
