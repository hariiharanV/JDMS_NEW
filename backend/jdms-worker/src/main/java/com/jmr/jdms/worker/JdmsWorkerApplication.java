package com.jmr.jdms.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * JDMS Next worker: runs untrusted-file processing (antivirus, text extraction, renditions, OCR) and indexing
 * jobs, isolated from the core. It serves no API; its HTTP port exposes only health and metrics.
 */
@SpringBootApplication
public class JdmsWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JdmsWorkerApplication.class, args);
    }
}
