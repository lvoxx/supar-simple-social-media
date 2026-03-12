package io.github.lvoxx.common_core.handler;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class GlobalErrorWebExceptionHandlerAutoConfig {

    @Bean
    @ConditionalOnMissingBean(GlobalErrorWebExceptionHandler.class)
    public GlobalErrorWebExceptionHandler globalErrorWebExceptionHandler(
        org.springframework.context.MessageSource messageSource) {
        return new GlobalErrorWebExceptionHandler(messageSource);
    }
}
