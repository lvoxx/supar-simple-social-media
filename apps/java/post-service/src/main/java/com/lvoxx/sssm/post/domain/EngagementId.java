package com.lvoxx.sssm.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key of {@code sssm.post_engagements}: {@code (post_id, actor_id, type)}. Its
 * uniqueness is what makes engagements idempotent — a user can hold at most one LIKE, one REPOST and
 * one BOOKMARK per post. Maps the infrastructure-owned columns exactly (the app runs
 * {@code ddl-auto=validate}).
 */
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

    protected EngagementId() {
        // for JPA
    }

    public EngagementId(UUID postId, UUID actorId, EngagementKind type) {
        this.postId = postId;
        this.actorId = actorId;
        this.type = type;
    }

    public UUID getPostId() {
        return postId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public EngagementKind getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EngagementId other)) {
            return false;
        }
        return Objects.equals(postId, other.postId)
                && Objects.equals(actorId, other.actorId)
                && type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(postId, actorId, type);
    }
}
