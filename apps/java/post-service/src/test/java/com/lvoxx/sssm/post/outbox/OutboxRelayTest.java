package com.lvoxx.sssm.post_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvoxx.sssm.post_service.domain.OutboxEvent;
import com.lvoxx.sssm.post_service.repository.OutboxRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Unit tests for the outbox relay: a successful Kafka publish stamps {@code publishedAt}; a failed
 * publish leaves the event unpublished for the next tick (at-least-once, never lost).
 */
class OutboxRelayTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);

    private OutboxRepository outbox;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxRepository.class);
        relay = new OutboxRelay(outbox, kafka, "sssm.post-events", 100);
    }

    @Test
    void drain_publishesUnpublishedEventsAndMarksThemPublished() {
        OutboxEvent event = new OutboxEvent(
                "post", UUID.randomUUID(), "PostCreated", new byte[] {1, 2, 3});
        when(outbox.findUnpublished(any(Limit.class))).thenReturn(List.of(event));
        doReturn(CompletableFuture.completedFuture(null))
                .when(kafka).send(any(ProducerRecord.class));

        relay.drain();

        verify(kafka).send(any(ProducerRecord.class));
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void drain_leavesEventUnpublishedWhenKafkaFails() {
        OutboxEvent event = new OutboxEvent(
                "post", UUID.randomUUID(), "PostCreated", new byte[] {1, 2, 3});
        when(outbox.findUnpublished(any(Limit.class))).thenReturn(List.of(event));
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        doReturn(failed).when(kafka).send(any(ProducerRecord.class));

        relay.drain();

        assertThat(event.getPublishedAt()).isNull();
    }
}
