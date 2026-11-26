package com.jmr.jdms.worker.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** A worker test against real services; it drives the job runner itself ({@code jdms.worker.auto-start=false}). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import({WorkerContainers.class, WorkerFixtures.class})
public @interface WorkerIntegrationTest {
}
