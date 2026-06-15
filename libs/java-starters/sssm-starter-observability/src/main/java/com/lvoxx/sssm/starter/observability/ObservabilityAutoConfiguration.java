package com.lvoxx.sssm.starter.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared observability defaults for every SSSM Spring Boot service.
 *
 * <p>Tags all metrics with the application name and deployment environment so that a single set of
 * Grafana dashboards and Prometheus alert rules can slice by {@code application} and {@code env}
 * across the whole fleet. Activated automatically via Spring Boot auto-configuration; services only
 * need this starter on the classpath.
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> sssmCommonTags(
            @Value("${spring.application.name:unknown}") String application,
            @Value("${sssm.env:local}") String env) {
        return registry -> registry.config().commonTags("application", application, "env", env);
    }
}
