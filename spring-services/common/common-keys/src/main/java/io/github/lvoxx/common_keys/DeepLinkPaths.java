package io.github.lvoxx.common_keys;

/**
 * Deep-link and internal URL path patterns used by notification-service
 * and message-notification-service when building {@code deepLink} fields.
 *
 * <p>Usage:
 * <pre>{@code
 * String link = DeepLinkPaths.post(postId);          // "/posts/<uuid>"
 * String link = DeepLinkPaths.userProfile(userId);   // "/users/<uuid>"
 * }</pre>
 */
public final class DeepLinkPaths {

    private DeepLinkPaths() {}

    // ── User ──────────────────────────────────────────────────────────────────

    /** Deep link to a user's public profile page. */
    public static String userProfile(Object userId) {
        return "/users/" + userId;
    }

    // ── Post ──────────────────────────────────────────────────────────────────

    /** Deep link to a specific post. */
    public static String post(Object postId) {
        return "/posts/" + postId;
    }

    /** Deep link to a specific post's comment section. */
    public static String postComments(Object postId) {
        return "/posts/" + postId + "/comments";
    }

    // ── Group ─────────────────────────────────────────────────────────────────

    /** Deep link to a group's main feed. */
    public static String group(Object groupId) {
        return "/groups/" + groupId;
    }

    /** Deep link to a group's pending join-request list. */
    public static String groupJoinRequests(Object groupId) {
        return "/groups/" + groupId + "/join-requests";
    }

    // ── Message ───────────────────────────────────────────────────────────────

    /** Deep link to a specific conversation. */
    public static String conversation(Object convId) {
        return "/messages/conversations/" + convId;
    }
}
