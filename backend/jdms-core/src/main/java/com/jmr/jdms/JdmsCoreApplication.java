package com.jmr.jdms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * JDMS Next core: REST API, domain modules and embedded workflow. Each sub-package of {@code com.jmr.jdms} is a
 * Spring Modulith application module.
 */
@SpringBootApplication
public class JdmsCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(JdmsCoreApplication.class, args);
    }
}
