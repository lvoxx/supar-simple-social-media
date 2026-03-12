package io.github.lvoxx.metrics_starter.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.WebFilter;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TracingMdcFilter.class)
    public WebFilter tracingMdcFilter() {
        return new TracingMdcFilter();
    }
}