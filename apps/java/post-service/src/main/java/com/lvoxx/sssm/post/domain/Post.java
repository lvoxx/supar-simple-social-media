package com.lvoxx.sssm.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A post (or reply). Maps to the infrastructure-owned, RANGE-partitioned {@code sssm.posts} table
 * (see {@code deploy/migrations/post-service/V1__baseline.sql}). The app runs with
 * {@code ddl-auto=validate} and never creates or alters this table, so every column mapped here
 * must match the baseline migration exactly.
 *
 * <p>The DB primary key is the composite {@code (id, created_at)} required by range partitioning,
 * but {@code id} alone identifies a post for the application, so only it is mapped as {@code @Id};
 * {@code created_at} is generated on insert and lands the row in the correct partition. A reply
 * carries {@code replyToPostId}; the parent's {@code replyCount} is kept in sync transactionally.
 * The engagement counts are denormalized for read speed (updated by the engagement flow).
 */
@Getter
@Entity
@Table(name = "posts")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "text", nullable = false, length = 280, updatable = false)
    private String text;

    @Column(name = "reply_to_post_id", updatable = false)
    private UUID replyToPostId;

    @Column(name = "reply_count", nullable = false)
    private long replyCount = 0;

    @Column(name = "like_count", nullable = false)
    private long likeCount = 0;

    @Column(name = "repost_count", nullable = false)
    private long repostCount = 0;

    @Column(name = "bookmark_count", nullable = false)
    private long bookmarkCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Post() {
        // for JPA
    }

    public Post(UUID authorId, String text, UUID replyToPostId) {
        this.authorId = authorId;
        this.text = text;
        this.replyToPostId = replyToPostId;
    }
}
