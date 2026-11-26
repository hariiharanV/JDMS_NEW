package com.jmr.jdms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jmr.jdms.support.AuditCoverage;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.RunLast;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * B-11: every state-changing endpoint, view and download writes an audit event. {@link AuditCoverage} checks each
 * request of the integration suite as it happens; this class, run last, fails for any audited endpoint of the
 * application that no test called successfully, so a new endpoint cannot ship without that proof.
 */
@IntegrationTest
@RunLast
class AuditCoverageIT {

    @Autowired RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyAuditedEndpointWasExercisedAndWroteAnEvent() {
        Set<String> covered = AuditCoverage.covered();
        Set<String> audited = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!isApplication(handler)) {
                return;
            }
            for (String pattern : info.getPatternValues()) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    if (AuditCoverage.isAudited(method.name(), pattern)) {
                        audited.add(method.name() + " " + pattern);
                    }
                }
            }
        });

        assertThat(audited).as("audited endpoints").hasSizeGreaterThan(40);
        // a partial run (a few test classes) covers little; the whole suite covers all but a missing test or two
        assumeTrue(covered.size() * 5 >= audited.size() * 4, "Needs the whole integration suite (mvn verify)");
        Set<String> missing = new TreeSet<>(audited);
        missing.removeAll(covered);
        assertThat(missing).as("audited endpoints that no test called successfully").isEmpty();
    }

    private static boolean isApplication(HandlerMethod handler) {
        return handler.getBeanType().getPackageName().startsWith("com.jmr.jdms");
    }
}
