package io.github.lvoxx.security_starter.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;
import org.springframework.web.server.WebFilter;

import io.github.lvoxx.security_starter.filter.UserPrincipalFilter;
import io.github.lvoxx.security_starter.properties.SecurityStarterProperties;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(SecurityStarterProperties.class)
public class SecurityAutoConfiguration implements WebFluxConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public WebFilter userPrincipalFilter(SecurityStarterProperties props) {
        return new UserPrincipalFilter(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrentUserArgumentResolver currentUserArgumentResolver() {
        return new CurrentUserArgumentResolver();
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(currentUserArgumentResolver());
    }
}