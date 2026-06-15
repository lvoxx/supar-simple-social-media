package com.lvoxx.sssm.post.outbox;

import com.lvoxx.sssm.post.domain.OutboxEvent;
import com.lvoxx.sssm.post.repository.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the transactional outbox to Kafka. A scheduled poller claims a batch of unpublished events
 * oldest-first, publishes each to the post-events topic keyed by the post id (preserving
 * per-post ordering), and stamps {@code publishedAt} only after the broker acknowledges. Delivery
 * is therefore at-least-once — consumers dedupe by {@code post_id} — but never lost.
 *
 * <p>Disabled via {@code sssm.outbox.enabled=false} (e.g. in integration tests with no broker).
 */
@Component
@ConditionalOnProperty(name = "sssm.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String EVENT_TYPE_HEADER = "event-type";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(
            OutboxRepository outbox,
            KafkaTemplate<String, byte[]> kafka,
            @Value("${sssm.outbox.topic}") String topic,
            @Value("${sssm.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    /**
     * Publishes one batch of unpublished events. {@code @Transactional} so the {@code publishedAt}
     * stamps flush together on commit; a publish failure leaves the row unpublished for the next
     * tick.
     */
    @Scheduled(fixedDelayString = "${sssm.outbox.poll-interval}")
    @Transactional
    public void drain() {
        List<OutboxEvent> batch = outbox.findUnpublished(Limit.of(batchSize));
        for (OutboxEvent event : batch) {
            if (!publish(event)) {
                // Preserve ordering: stop at the first failure and retry from here next tick.
                break;
            }
        }
    }

    private boolean publish(OutboxEvent event) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                topic, event.getAggregateId().toString(), event.getPayload());
        record.headers().add(
                EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8));
        try {
            // Block until the broker acks so we only mark published on confirmed delivery.
            kafka.send(record).get();
            event.markPublished(Instant.now());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted publishing outbox event {}", event.getId());
            return false;
        } catch (ExecutionException e) {
            log.warn("Failed to publish outbox event {}, will retry", event.getId(), e);
            return false;
        }
    }
}
