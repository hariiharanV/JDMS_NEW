package com.jmr.jdms.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Layering inside each module (CLAUDE.md coding conventions). Rules may match no classes until modules fill in. */
@AnalyzeClasses(packages = "com.jmr.jdms", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringArchTest {

    @ArchTest
    static final ArchRule domainDoesNotDependOnWebOrInfra = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..web..", "..infra..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainDoesNotDependOnSpringWeb = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nativeSqlOnlyInInfraAdapters = noClasses()
            .that().resideOutsideOfPackage("..infra..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc.core..")
            .because("CLAUDE.md: native SQL lives in per-database adapter classes; JPA repositories elsewhere");

    /** CLAUDE.md rule 3 / B-04: no endpoint without an authorization check. */
    @ArchTest
    static final ArchRule everyEndpointHasAnAuthorizationAnnotation = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areAnnotatedWith(RequestMapping.class)
            .or().areAnnotatedWith(GetMapping.class).or().areAnnotatedWith(PostMapping.class)
            .or().areAnnotatedWith(PutMapping.class).or().areAnnotatedWith(PatchMapping.class)
            .or().areAnnotatedWith(DeleteMapping.class)
            .should(new ArchCondition<JavaMethod>("be authorized with @PreAuthorize (method or class)") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean authorized = method.isAnnotatedWith(PreAuthorize.class)
                            || method.getOwner().isAnnotatedWith(PreAuthorize.class);
                    if (!authorized) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " has no @PreAuthorize"));
                    }
                }
            });

    @ArchTest
    static final ArchRule noJavaUtilDate = noClasses()
            .that().doNotHaveFullyQualifiedName("com.jmr.jdms.workflow.domain.FlowableDates")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Date")
            .because("CLAUDE.md: use java.time, never Date (FlowableDates adapts Flowable's Date-based task API)");
}
