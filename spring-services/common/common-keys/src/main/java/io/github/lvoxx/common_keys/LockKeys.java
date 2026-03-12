package io.github.lvoxx.common_keys;

/**
 * Factory methods for distributed-lock keys used across the platform.
 *
 * <p>All lock keys follow the pattern {@code lock:<domain>:<context>}.
 * Using a factory method (rather than a bare prefix) makes it impossible
 * to forget a parameter and silently obtain a coarser-grained lock.
 *
 * <p>Usage:
 * <pre>{@code
 * String key = LockKeys.follow(followerId, targetId);
 * lockService.withLock(key, ttlMs, () -> ...);
 * }</pre>
 */
public final class LockKeys {

    private LockKeys() {}

    // ── User domain ───────────────────────────────────────────────────────────

    /**
     * Prevents duplicate follow/unfollow races for the same (follower, target) pair.
     *
     * @param followerId UUID of the user initiating the follow
     * @param targetId   UUID of the user being followed
     */
    public static String follow(Object followerId, Object targetId) {
        return "lock:follow:" + followerId + ":" + targetId;
    }

    /**
     * Prevents concurrent avatar updates for the same user.
     *
     * @param userId UUID of the user updating their avatar
     */
    public static String userProfileUpdate(Object userId) {
        return "lock:user:profile:update:" + userId;
    }

    // ── Post domain ───────────────────────────────────────────────────────────

    /**
     * Prevents duplicate like/unlike races for the same (user, post) pair.
     *
     * @param userId UUID of the user liking the post
     * @param postId UUID of the post being liked
     */
    public static String postLike(Object userId, Object postId) {
        return "lock:post:like:" + userId + ":" + postId;
    }

    // ── Group domain ──────────────────────────────────────────────────────────

    /**
     * Prevents concurrent join requests for the same (user, group) pair.
     *
     * @param userId  UUID of the user joining
     * @param groupId UUID of the target group
     */
    public static String groupJoin(Object userId, Object groupId) {
        return "lock:group:join:" + userId + ":" + groupId;
    }

    /**
     * Prevents concurrent membership count updates for a group.
     *
     * @param groupId UUID of the group
     */
    public static String groupMemberCount(Object groupId) {
        return "lock:group:member-count:" + groupId;
    }

    // ── Message domain ────────────────────────────────────────────────────────

    /**
     * Prevents duplicate conversation creation between two users.
     *
     * @param userA UUID of one participant
     * @param userB UUID of the other participant
     */
    public static String directConversation(Object userA, Object userB) {
        // Canonical ordering so (A,B) == (B,A)
        String a = userA.toString();
        String b = userB.toString();
        return a.compareTo(b) <= 0
                ? "lock:conversation:direct:" + a + ":" + b
                : "lock:conversation:direct:" + b + ":" + a;
    }

    // ── Bookmark domain ───────────────────────────────────────────────────────

    public static String postBookmark(Object userId, Object postId) {
        return "lock:post:bookmark:" + userId + ":" + postId;
    }

    public static String postView(Object userId, Object postId) {
        return "lock:post:view:" + userId + ":" + postId;
    }

    public static String bookmarkCollection(Object userId, Object collectionId) {
        return "lock:bookmark:collection:" + userId + ":" + collectionId;
    }

    public static String commentLike(Object userId, Object commentId) {
        return "lock:comment:like:" + userId + ":" + commentId;
    }

    /**
     * Prevents duplicate block/unblock races for the same (blocker, blocked) pair.
     *
     * @param blockerId UUID of the user performing the block
     * @param blockedId UUID of the user being blocked
     */
    public static String block(Object blockerId, Object blockedId) {
        return "lock:user:block:" + blockerId + ":" + blockedId;
    }
}
