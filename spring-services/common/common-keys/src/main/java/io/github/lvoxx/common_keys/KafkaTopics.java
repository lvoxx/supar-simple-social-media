package io.github.lvoxx.common_keys;

/**
 * Centralised registry of every Kafka topic name used across the platform.
 *
 * <p>
 * Import this class instead of hard-coding topic strings in producers,
 * consumers, and {@code @KafkaListener} annotations (via SpEL:
 * {@code #{T(io.github.lvoxx.common.keys.KafkaTopics).POST_CREATED}}).
 *
 * <p>
 * Naming convention: {@code <domain>.<action>} in dot-notation, all lower-case.
 */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    // ── User domain ───────────────────────────────────────────────────────────

    public static class User {
        /** Published by user-service when a user updates their profile fields. */
        public static final String USER_PROFILE_UPDATED = "user.profile.updated";

        /** Published by user-service when a user changes their avatar image. */
        public static final String USER_AVATAR_CHANGED = "user.avatar.changed";

        /**
         * Published by user-service when a user changes their background (banner)
         * image.
         */
        public static final String USER_BACKGROUND_CHANGED = "user.background.changed";

        /** Published by user-service when a follow relationship is created. */
        public static final String USER_FOLLOWED = "user.followed";

        /** Published by user-service when a follow relationship is removed. */
        public static final String USER_UNFOLLOWED = "user.unfollowed";

    }
    // ── Post domain ───────────────────────────────────────────────────────────

    public static class Post {
        /** Published by post-service when a new post is created and persisted. */
        public static final String POST_CREATED = "post.created";

        /** Published by post-service when a user likes a post. */
        public static final String POST_LIKED = "post.liked";

        /** Published by post-service when a user reposts a post. */
        public static final String POST_REPOSTED = "post.reposted";

        /** Published by post-service when a post is deleted. */
        public static final String POST_DELETED = "post.deleted";

        /** Published by post-service when a user unlikes a post. */
        public static final String POST_UNLIKED = "post.unliked";

        /** Published by post-service when a user bookmarks a post. */
        public static final String POST_BOOKMARKED = "post.bookmarked";

        /** Published by post-service when a user removes a bookmark. */
        public static final String POST_UNBOOKMARKED = "post.unbookmarked";
    }

    // ── PostInteraction domain ─────────────────────────────────────────────────

    public static class PostInteraction {
        public static final String POST_LIKED = "post.liked";
        public static final String POST_UNLIKED = "post.unliked";
        public static final String POST_REPOSTED = "post.reposted";
        public static final String POST_VIEWED = "post.viewed";
    }

    // ── Comment domain ────────────────────────────────────────────────────────

    public static class Comment {
        /**
         * Published by comment-service when a top-level comment or reply is created.
         */
        public static final String COMMENT_CREATED = "comment.created";

        /** Published by comment-service when a user likes a comment. */
        public static final String COMMENT_LIKED = "comment.liked";

        /** Published by comment-service when a comment is deleted. */
        public static final String COMMENT_DELETED = "comment.deleted";
    }

    // ── Bookmark domain ────────────────────────────────────────────────────────

    public static class Bookmark {
        public static final String BOOKMARK_ADDED = "bookmark.added";
        public static final String BOOKMARK_REMOVED = "bookmark.removed";
    }

    // ── Notification domain ────────────────────────────────────────────────────

    public static class Notification {
        public static final String NOTIFICATION_CREATED = "notification.created";
        public static final String NOTIFICATION_READ = "notification.read";
    }

    // ── Group domain ──────────────────────────────────────────────────────────

    public static class Group {
        /** Published by group-service when a new group is created. */
        public static final String GROUP_CREATED = "group.created";

        /**
         * Published by group-service when a user is accepted into a PUBLIC group
         * or when a PENDING request is approved by a MODERATOR.
         */
        public static final String GROUP_MEMBER_JOINED = "group.member.joined";
    }

    // ── Message domain ────────────────────────────────────────────────────────

    public static class Message {
        /** Published by private-message-service when a new conversation is created. */
        public static final String CONVERSATION_CREATED = "conversation.created";

        /** Published by private-message-service when a message is sent. */
        public static final String MESSAGE_SENT = "message.sent";

        /** Published when a message is delivered to the recipient. */
        public static final String MESSAGE_DELIVERED = "message.delivered";

        /** Published when a message is read by the recipient. */
        public static final String MESSAGE_READ = "message.read";

        /** Published when a reaction is added to a message. */
        public static final String MESSAGE_REACTION_ADDED = "message.reaction.added";

        /** Published when a reaction is removed from a message. */
        public static final String MESSAGE_REACTION_REMOVED = "message.reaction.removed";

        /** Published when a message is deleted. */
        public static final String MESSAGE_DELETED = "message.deleted";

        /** Published when conversation settings are updated. */
        public static final String CONVERSATION_SETTINGS_UPDATED = "conversation.settings.updated";
    }

    // ── Media domain ──────────────────────────────────────────────────────────

    public static class Media {
        /**
         * Published by media-service when an asset finishes transcoding / CDN upload.
         */
        public static final String MEDIA_UPLOAD_COMPLETED = "media.upload.completed";
    }
}
