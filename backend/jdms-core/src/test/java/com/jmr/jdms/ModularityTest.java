package com.jmr.jdms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** Modules talk to each other only through their {@code api} package or domain events (CLAUDE.md). */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(JdmsCoreApplication.class);

    @Test
    void applicationModulesRespectTheirBoundaries() {
        modules.verify();
    }

    /** {@code contracts} and {@code platform} come from JARs that share the base package. */
    @Test
    void everyModuleFromTheRepositoryLayoutIsDetected() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("tenant", "identity", "metadata", "document", "access", "workflow",
                        "records", "audit", "search", "integration", "shared", "contracts", "platform");
    }
}
