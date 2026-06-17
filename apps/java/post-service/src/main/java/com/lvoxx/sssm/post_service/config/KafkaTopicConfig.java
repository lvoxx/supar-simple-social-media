package com.lvoxx.sssm.post_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the post-events topic so {@code KafkaAdmin} provisions it in environments where topic
 * auto-creation is on (local dev). In cloud the topic is created by infrastructure; this bean is a
 * harmless no-op when the topic already exists.
 *
 * <p>Gated on the same {@code sssm.outbox.enabled} flag as the relay: when disabled (integration
 * tests with no broker) no {@code NewTopic} bean exists, so {@code KafkaAdmin} never tries to reach
 * a broker at startup.
 */
@Configuration
@ConditionalOnProperty(name = "sssm.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    @Bean
    public NewTopic postEventsTopic(@Value("${sssm.outbox.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(6).replicas(1).build();
    }
}
