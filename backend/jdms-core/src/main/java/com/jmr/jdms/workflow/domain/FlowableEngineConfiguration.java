package com.jmr.jdms.workflow.domain;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Engine settings (ADR-0013): process expressions may reference only the approval beans, never arbitrary
 * application beans (data source, mappers...); IDs are UUIDs.
 */
@Configuration(proxyBeanMethods = false)
class FlowableEngineConfiguration {

    static final Set<String> EXPRESSION_BEANS = Set.of("approvalPlan", "approvalTaskSetup", "approvalOutcome");

    @Bean
    EngineConfigurationConfigurer<SpringProcessEngineConfiguration> jdmsProcessEngineConfigurer(
            BeanFactory beanFactory) {
        return configuration -> {
            configuration.setBeans(new RestrictedBeans(beanFactory, EXPRESSION_BEANS));
            configuration.setIdGenerator(new StrongUuidGenerator());
        };
    }

    /** Resolves the allowed beans lazily (they depend on the engine's services, so they cannot be injected here). */
    static final class RestrictedBeans extends AbstractMap<Object, Object> {

        private final BeanFactory beanFactory;
        private final Set<String> names;

        RestrictedBeans(BeanFactory beanFactory, Set<String> names) {
            this.beanFactory = beanFactory;
            this.names = Set.copyOf(names);
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String name && names.contains(name);
        }

        @Override
        public Object get(Object key) {
            return containsKey(key) ? beanFactory.getBean((String) key) : null;
        }

        @Override
        public Set<Entry<Object, Object>> entrySet() {
            return names.stream().map(name -> Map.entry((Object) name, beanFactory.getBean(name)))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
