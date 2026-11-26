package com.jmr.jdms.support;

import com.jmr.jdms.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application against real PostgreSQL, with test-signed tokens. All classes using it share one Spring context
 * (and database), so test data must be unique per test ({@link TestTenants}). Every request is checked for its audit
 * event ({@link AuditCoverage}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class, TestTenants.class, TestMetadata.class,
        S3TestAccess.class, AuditCoverage.class})
public @interface IntegrationTest {
}
