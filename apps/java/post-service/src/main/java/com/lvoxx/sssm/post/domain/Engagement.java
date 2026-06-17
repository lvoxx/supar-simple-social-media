package com.lvoxx.sssm.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A user's engagement with a post (a like, repost or bookmark). The row's existence IS the
 * engagement; removing it (unlike/unrepost/unbookmark) deletes the row. Maps to the
 * infrastructure-owned {@code sssm.post_engagements} table
 * (see {@code deploy/migrations/post-service/V2__engagement.sql}); the app runs with
 * {@code ddl-auto=validate} and never creates or alters it.
 *
 * <p>The denormalized counts on {@link Post} (like/repost/bookmark) are kept in sync transactionally
 * by {@link com.lvoxx.sssm.post.service.EngagementService}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "post_engagements")
public class Engagement {

    @EmbeddedId
    private EngagementId id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Engagement(EngagementId id) {
        this.id = id;
    }
}
