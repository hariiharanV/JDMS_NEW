package com.jmr.jdms.integration.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class IntegrationWebConfiguration implements WebMvcConfigurer {

    private final IntegrationCallInterceptor interceptor;

    IntegrationWebConfiguration(IntegrationCallInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/integration/**");
    }
}
