package io.github.lvoxx.common_core.validation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import jakarta.validation.Validator;

@AutoConfiguration
public class ReactiveValidatorAutoConfig {

    @Bean
    @Scope("prototype")
    @ConditionalOnMissingBean
    public Validator validator() {
        return new LocalValidatorFactoryBean();
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveValidator.class)
    public ReactiveValidator reactiveValidator(Validator validator) {
        return new ReactiveValidator(validator);
    }
}
