package com.lvoxx.sssm.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A transactional-outbox row. Written in the SAME transaction as the post change it describes, so
 * the post and its event commit atomically. The {@link com.lvoxx.sssm.post.outbox.OutboxRelay}
 * later publishes unpublished rows to Kafka and stamps {@code publishedAt}.
 *
 * <p>{@code payload} is the serialized Protobuf message ({@code sssm.event.v1.PostCreated} /
 * {@code PostDeleted}) so Java and Go consumers share one wire format. Maps to the
 * infrastructure-owned {@code sssm.outbox_events} table.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false)
    private byte[] payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, byte[] payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    /** Marks this event as published; dirty-checking flushes the {@code published_at} on commit. */
    public void markPublished(Instant at) {
        this.publishedAt = at;
    }
}
