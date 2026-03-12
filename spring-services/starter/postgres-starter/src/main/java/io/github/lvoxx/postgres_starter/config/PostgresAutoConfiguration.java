package io.github.lvoxx.postgres_starter.config;

import java.util.UUID;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.ReactiveAuditorAware;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;

import io.github.lvoxx.common_core.util.ReactiveContextUtil;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.data.r2dbc.core.R2dbcEntityTemplate")
@EnableR2dbcAuditing
public class PostgresAutoConfiguration {

    /**
     * Provides @CreatedBy / @LastModifiedBy values from Reactor Context.
     * Falls back to a nil UUID for anonymous / system requests.
     */
    @Bean
    @ConditionalOnMissingBean(ReactiveAuditorAware.class)
    public ReactiveAuditorAware<UUID> reactiveAuditorAware() {
        return () -> ReactiveContextUtil.getCurrentUserId()
                .defaultIfEmpty(new UUID(0, 0));
    }
}