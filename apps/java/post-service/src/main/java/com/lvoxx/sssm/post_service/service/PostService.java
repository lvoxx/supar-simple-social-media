package com.lvoxx.sssm.post_service.service;

import com.lvoxx.sssm.post_service.domain.Post;
import com.lvoxx.sssm.post_service.error.BadRequestException;
import com.lvoxx.sssm.post_service.error.ForbiddenException;
import com.lvoxx.sssm.post_service.error.NotFoundException;
import com.lvoxx.sssm.post_service.outbox.OutboxEventFactory;
import com.lvoxx.sssm.post_service.repository.OutboxRepository;
import com.lvoxx.sssm.post_service.repository.PostRepository;
import com.lvoxx.sssm.post_service.web.dto.CreatePostRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post lifecycle: creating posts and thread replies, reading them, listing an author's posts or a
 * post's replies (cursor-paginated), and deleting one's own posts. Every write also appends a
 * Protobuf domain event to the transactional outbox in the SAME transaction, so the post change and
 * its event commit atomically (the outbox relay publishes them to Kafka afterwards).
 *
 * <p>The author is always the gateway-forwarded identity ({@code authorId}), never a request body
 * field.
 */
@Service
public class PostService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final PostRepository posts;
    private final OutboxRepository outbox;

    public PostService(PostRepository posts, OutboxRepository outbox) {
        this.posts = posts;
        this.outbox = outbox;
    }

    @Transactional
    public Post create(UUID authorId, CreatePostRequest req) {
        UUID replyTo = req.replyToPostId();
        if (replyTo != null && !posts.existsById(replyTo)) {
            throw new NotFoundException("Cannot reply to a post that does not exist");
        }
        // saveAndFlush so the DB-generated id and created_at are populated before the event is built.
        Post saved = posts.saveAndFlush(new Post(authorId, req.text(), replyTo));
        if (replyTo != null) {
            posts.incrementReplyCount(replyTo);
        }
        outbox.save(OutboxEventFactory.postCreated(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public Post getById(UUID id) {
        return posts.findById(id)
                .orElseThrow(() -> new NotFoundException("No post with id " + id));
    }

    /** Deletes the caller's own post; deleting a reply also decrements the parent's reply count. */
    @Transactional
    public void delete(UUID authorId, UUID id) {
        Post post = getById(id);
        if (!post.getAuthorId().equals(authorId)) {
            throw new ForbiddenException("You can only delete your own posts");
        }
        if (post.getReplyToPostId() != null) {
            posts.decrementReplyCount(post.getReplyToPostId());
        }
        posts.delete(post);
        outbox.save(OutboxEventFactory.postDeleted(post, Instant.now()));
    }

    /** Posts by an author, newest first. */
    @Transactional(readOnly = true)
    public CursorPage<Post> listByAuthor(UUID authorId, String cursor, Integer limit) {
        Cursor c = Cursor.decode(cursor);
        int size = clampLimit(limit);
        List<Post> rows = posts.pageByAuthor(
                authorId, c == null ? null : c.ts(), c == null ? null : c.id(),
                Limit.of(size + 1));
        return toPage(rows, size);
    }

    /** Replies to a post (one thread level), newest first. */
    @Transactional(readOnly = true)
    public CursorPage<Post> listReplies(UUID postId, String cursor, Integer limit) {
        getById(postId); // 404 if the thread root is gone
        Cursor c = Cursor.decode(cursor);
        int size = clampLimit(limit);
        List<Post> rows = posts.pageReplies(
                postId, c == null ? null : c.ts(), c == null ? null : c.id(),
                Limit.of(size + 1));
        return toPage(rows, size);
    }

    /** Drops the (size+1) probe row and builds the next cursor from the last included row. */
    private CursorPage<Post> toPage(List<Post> rows, int size) {
        boolean hasMore = rows.size() > size;
        List<Post> page = hasMore ? rows.subList(0, size) : rows;
        String next = null;
        if (hasMore && !page.isEmpty()) {
            Post last = page.get(page.size() - 1);
            next = new Cursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new CursorPage<>(List.copyOf(page), next);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /**
     * Opaque keyset cursor: the {@code created_at} and id of the last row of the previous page,
     * encoded as base64url of {@code "<instant>|<uuid>"}.
     */
    private record Cursor(Instant ts, UUID id) {

        String encode() {
            String raw = ts.toString() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(
                        Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.indexOf('|');
                return new Cursor(
                        Instant.parse(raw.substring(0, sep)),
                        UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new BadRequestException("Invalid cursor");
            }
        }
    }
}
