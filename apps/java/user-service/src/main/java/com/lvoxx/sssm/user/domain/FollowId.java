package com.lvoxx.sssm.user.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link Follow}: the {@code (follower_id, followee_id)} pair. Field
 * names and types must match the {@code @Id} fields on {@link Follow}.
 */
public class FollowId implements Serializable {

    private UUID followerId;
    private UUID followeeId;

    public FollowId() {
        // for JPA
    }

    public FollowId(UUID followerId, UUID followeeId) {
        this.followerId = followerId;
        this.followeeId = followeeId;
    }

    public UUID getFollowerId() {
        return followerId;
    }

    public UUID getFolloweeId() {
        return followeeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FollowId other)) {
            return false;
        }
        return Objects.equals(followerId, other.followerId)
                && Objects.equals(followeeId, other.followeeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(followerId, followeeId);
    }
}
