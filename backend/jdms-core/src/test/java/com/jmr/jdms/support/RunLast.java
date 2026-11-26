package com.jmr.jdms.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Comparator;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

/**
 * Runs the annotated test class after all others, for checks over what the whole suite did (AuditCoverageIT).
 * Enabled for all test classes in {@code junit-platform.properties}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunLast {

    /** Keeps the discovery order and moves {@link RunLast} classes to the end. */
    class Orderer implements ClassOrderer {

        @Override
        public void orderClasses(ClassOrdererContext context) {
            context.getClassDescriptors().sort(Comparator.comparing(
                    (ClassDescriptor descriptor) -> descriptor.isAnnotated(RunLast.class)));
        }
    }
}
