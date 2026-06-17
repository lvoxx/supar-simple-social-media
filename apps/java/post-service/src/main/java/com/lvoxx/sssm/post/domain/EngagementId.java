package com.lvoxx.sssm.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Composite primary key of {@code sssm.post_engagements}: {@code (post_id, actor_id, type)}. Its
 * uniqueness is what makes engagements idempotent — a user can hold at most one LIKE, one REPOST and
 * one BOOKMARK per post. Maps the infrastructure-owned columns exactly (the app runs
 * {@code ddl-auto=validate}).
 */
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Embeddable
public class EngagementId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private EngagementKind type;

}
