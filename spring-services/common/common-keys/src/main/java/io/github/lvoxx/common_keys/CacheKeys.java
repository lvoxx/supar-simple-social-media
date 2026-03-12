package io.github.lvoxx.common_keys;

/**
 * Centralised Redis cache key prefixes used across the platform.
 *
 * <p>
 * Build a full key by combining the prefix with a unique identifier:
 * 
 * <pre>{@code
 * String key = CacheKeys.USER_PROFILE + userId;
 * String key = CacheKeys.POST_DETAIL + postId;
 * }</pre>
 *
 * <p>
 * Naming convention: {@code <domain>:<entity>:<qualifier>:}
 * (trailing colon so consumer only needs to append the ID).
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    // ── User ──────────────────────────────────────────────────────────────────
    public static final class UserService {
        /** Full user profile keyed by userId (UUID). TTL: 5 min. */
        public static final String PROFILE = "user:profile:";

        /** Full user profile keyed by username (String). TTL: 5 min. */
        public static final String PROFILE_USERNAME = "user:profile:username:";

        /** Follower count keyed by userId. TTL: 1 min. */
        public static final String FOLLOWER_COUNT = "user:followers:count:";

        /** Following count keyed by userId. TTL: 1 min. */
        public static final String FOLLOWING_COUNT = "user:following:count:";

        /** Full user profile keyed by userId (UUID). TTL: 5 min. */
        public static final String USER_PROFILE = "user:profile:";

        /** Full user profile keyed by username (String). TTL: 5 min. */
        public static final String USER_PROFILE_USERNAME = "user:profile:username:";

        /** Follower count keyed by userId. TTL: 1 min. */
        public static final String USER_FOLLOWER_COUNT = "user:followers:count:";

        /** Following count keyed by userId. TTL: 1 min. */
        public static final String USER_FOLLOWING_COUNT = "user:following:count:";

        /** Followers page keyed by "{userId}:{cursor}:{size}". TTL: 30 s. */
        public static final String FOLLOWERS_LIST = "user:followers:list:";

        /** Following page keyed by "{userId}:{cursor}:{size}". TTL: 30 s. */
        public static final String FOLLOWING_LIST = "user:following:list:";

        /** Block status keyed by "{blockerId}:{blockedId}". TTL: 5 min. */
        public static final String BLOCK_STATUS = "user:block:status:";

        /** User privacy/messaging settings keyed by userId. TTL: 5 min. */
        public static final String USER_SETTINGS_KEY = "user:settings:";

        /** Pending follow requests keyed by targetUserId. TTL: 60 s. */
        public static final String FOLLOW_REQUESTS_LIST = "user:follow-requests:";
    }

    // ── Post ──────────────────────────────────────────────────────────────────

    public static class Post {
        /** Full post detail keyed by postId (UUID). TTL: 2 min. */
        public static final String POST_DETAIL = "post:detail:";

        /** Home feed page keyed by userId + cursor. TTL: 30 s. */
        public static final String POST_HOME_FEED = "post:feed:home:";

        /** Explore / trending feed. TTL: 60 s. */
        public static final String POST_EXPLORE_FEED = "post:feed:explore";
    }

    // ── Group ─────────────────────────────────────────────────────────────────

    public static class Group {
        /** Group metadata keyed by groupId (UUID). TTL: 5 min. */
        public static final String GROUP_DETAIL = "group:detail:";

        /** Member count keyed by groupId. TTL: 1 min. */
        public static final String GROUP_MEMBER_COUNT = "group:member:count:";
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public static class Search {
        /** Trending hashtags. TTL: 5 min. */
        public static final String SEARCH_TRENDING_HASHTAGS = "search:trending:hashtags";

        /** Search suggestion autocomplete keyed by query prefix. TTL: 60 s. */
        public static final String SEARCH_SUGGESTIONS = "search:suggestions:";
    }

    // ── Notification ──────────────────────────────────────────────────────────

    public static class Notification {
        /** Unread notification count keyed by userId. TTL: 30 s. */
        public static final String NOTIFICATION_UNREAD_COUNT = "notification:unread:count:";
    }

    // ── PostInteraction ───────────────────────────────────────────────────────

    public static class PostInteraction {
        public static final String POST_COUNTS = "post:counts:";
        public static final String POST_LIKED_BY = "post:liked-by:";
    }

    // ── Bookmark ──────────────────────────────────────────────────────────────

    public static class Bookmark {
        public static final String COLLECTION_LIST = "bookmark:collections:";
    }

    // ── Comment ───────────────────────────────────────────────────────────────

    public static class Comment {
        public static final String COMMENT_COUNT = "comment:count:";
    }

    // ── Message ───────────────────────────────────────────────────────────────

    public static class Message {
        public static final String CONV_PARTICIPANTS = "msg:participants:";
        public static final String UNREAD_COUNT = "msg:unread:";
        public static final String USER_SETTINGS = "msg:settings:user:";
        public static final String WS_ONLINE = "msg:notif:ws-online:";
        public static final String NOTIF_BATCH = "msg:notif:batch:";
        public static final String NOTIF_SETTINGS = "msg:notif:settings:";
        public static final String NOTIF_CONV_SETTINGS = "msg:notif:conv-settings:";
    }
}