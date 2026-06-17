package com.lvoxx.sssm.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A directed follow edge: {@code followerId} follows {@code followeeId}. Maps to the
 * infrastructure-owned {@code sssm.follows} table (composite primary key, see
 * {@code deploy/migrations/user-service/V1__baseline.sql}). The DB enforces {@code no_self_follow}
 * and cascades deletes when either profile is removed.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "follows")
@IdClass(FollowId.class)
public class Follow {

    @Id
    @Column(name = "follower_id", nullable = false, updatable = false)
    private UUID followerId;

    @Id
    @Column(name = "followee_id", nullable = false, updatable = false)
    private UUID followeeId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Follow(UUID followerId, UUID followeeId) {
        this.followerId = followerId;
        this.followeeId = followeeId;
    }
}
