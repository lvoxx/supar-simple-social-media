package com.lvoxx.sssm.post.repository;

import com.lvoxx.sssm.post.domain.Post;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, UUID> {

    /**
     * Posts by {@code authorId}, newest first, with compound keyset pagination on
     * {@code (created_at, id)} so rows sharing a {@code created_at} are never skipped. Pass
     * {@code ts}/{@code id} = null for the first page.
     */
    @Query("""
            select p from Post p
            where p.authorId = :authorId
              and (:ts is null
                   or p.createdAt < :ts
                   or (p.createdAt = :ts and p.id < :id))
            order by p.createdAt desc, p.id desc
            """)
    List<Post> pageByAuthor(
            @Param("authorId") UUID authorId,
            @Param("ts") Instant ts,
            @Param("id") UUID id,
            Limit limit);

    /** Replies to {@code postId} (one level of a thread), newest first, keyset-paginated. */
    @Query("""
            select p from Post p
            where p.replyToPostId = :postId
              and (:ts is null
                   or p.createdAt < :ts
                   or (p.createdAt = :ts and p.id < :id))
            order by p.createdAt desc, p.id desc
            """)
    List<Post> pageReplies(
            @Param("postId") UUID postId,
            @Param("ts") Instant ts,
            @Param("id") UUID id,
            Limit limit);

    @Modifying
    @Query("update Post p set p.replyCount = p.replyCount + 1 where p.id = :id")
    void incrementReplyCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.replyCount = p.replyCount - 1 where p.id = :id and p.replyCount > 0")
    void decrementReplyCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.likeCount = p.likeCount + 1 where p.id = :id")
    void incrementLikeCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.likeCount = p.likeCount - 1 where p.id = :id and p.likeCount > 0")
    void decrementLikeCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.repostCount = p.repostCount + 1 where p.id = :id")
    void incrementRepostCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.repostCount = p.repostCount - 1 "
            + "where p.id = :id and p.repostCount > 0")
    void decrementRepostCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.bookmarkCount = p.bookmarkCount + 1 where p.id = :id")
    void incrementBookmarkCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.bookmarkCount = p.bookmarkCount - 1 "
            + "where p.id = :id and p.bookmarkCount > 0")
    void decrementBookmarkCount(@Param("id") UUID id);
}
