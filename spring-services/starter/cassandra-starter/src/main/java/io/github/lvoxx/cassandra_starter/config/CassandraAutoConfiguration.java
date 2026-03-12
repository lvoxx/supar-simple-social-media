package io.github.lvoxx.cassandra_starter.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.cassandra.ReactiveSession;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.convert.CassandraConverter;

@AutoConfiguration
@ConditionalOnClass(ReactiveSession.class)
public class CassandraAutoConfiguration {

    /**
     * {@link ReactiveCassandraTemplate} is provided here only as a fallback.
     * Spring Boot auto-configures it when {@code spring.cassandra.*} properties
     * are present. schema-action must always be NONE (schema managed by K8S).
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactiveCassandraTemplate reactiveCassandraTemplate(
            ReactiveSession reactiveSession,
            CassandraConverter converter) {
        return new ReactiveCassandraTemplate(reactiveSession, converter);
    }
}