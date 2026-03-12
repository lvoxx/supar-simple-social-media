package io.github.lvoxx.elasticsearch_starter.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchTemplate;

/**
 * Elasticsearch auto-configuration.
 * Spring Boot auto-configures {@link ReactiveElasticsearchTemplate} from
 * {@code spring.elasticsearch.*} properties. This class is a placeholder
 * that guarantees the starter is on the classpath and can be extended.
 */
@AutoConfiguration
@ConditionalOnClass(ReactiveElasticsearchTemplate.class)
public class ElasticsearchAutoConfiguration {
    // Spring Boot auto-configures ReactiveElasticsearchClient from YAML.
    // Add @Bean overrides here only when custom behaviour is required.
}
