package com.jmr.jdms.shared.tenant;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link TenantIdentifierResolver} with Hibernate (discriminator multi-tenancy via {@code @TenantId}). */
@Configuration(proxyBeanMethods = false)
class TenantHibernateConfiguration {

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer() {
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                new TenantIdentifierResolver());
    }
}
