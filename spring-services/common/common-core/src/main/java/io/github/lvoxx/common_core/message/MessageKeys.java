package io.github.lvoxx.common_core.message;

public final class MessageKeys {
    private MessageKeys() {
    }

    // Users
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String ALREADY_FOLLOWING = "ALREADY_FOLLOWING";
    public static final String NOT_FOLLOWING = "NOT_FOLLOWING";

    // Posts
    public static final String POST_NOT_FOUND = "POST_NOT_FOUND";
    public static final String POST_CONTENT_REJECTED = "POST_CONTENT_REJECTED";
    public static final String POST_ALREADY_LIKED = "POST_ALREADY_LIKED";

    // Media
    public static final String MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND";
    public static final String MEDIA_PROCESSING = "MEDIA_STILL_PROCESSING";
    public static final String MEDIA_REJECTED = "MEDIA_CONTENT_REJECTED";
    public static final String FILE_TOO_LARGE = "FILE_SIZE_EXCEEDS_LIMIT";
    public static final String INVALID_FILE_TYPE = "INVALID_FILE_TYPE";

    // Groups
    public static final String GROUP_NOT_FOUND = "GROUP_NOT_FOUND";
    public static final String ALREADY_MEMBER = "ALREADY_GROUP_MEMBER";
    public static final String NOT_MEMBER = "NOT_GROUP_MEMBER";
    public static final String INSUFFICIENT_ROLE = "INSUFFICIENT_GROUP_ROLE";
    public static final String OWNER_CANNOT_LEAVE = "OWNER_CANNOT_LEAVE_GROUP";
    public static final String MAX_PINS_REACHED = "MAX_PINNED_POSTS_REACHED";

    // Messages
    public static final String CONV_NOT_FOUND = "CONVERSATION_NOT_FOUND";
    public static final String MSG_NOT_FOUND = "MESSAGE_NOT_FOUND";
    public static final String MSG_EDIT_EXPIRED = "MESSAGE_EDIT_TIME_EXPIRED";
    public static final String DM_NOT_ALLOWED = "DIRECT_MESSAGE_NOT_ALLOWED";

    // Comments
    public static final String COMMENT_NOT_FOUND = "COMMENT_NOT_FOUND";
    public static final String MAX_COMMENT_DEPTH_REACHED = "MAX_COMMENT_DEPTH_REACHED";

    // Bookmarks
    public static final String COLLECTION_NOT_FOUND = "COLLECTION_NOT_FOUND";
    public static final String ALREADY_BOOKMARKED = "ALREADY_BOOKMARKED";

    // Notifications
    public static final String NOTIFICATION_NOT_FOUND = "NOTIFICATION_NOT_FOUND";

    // Post interactions
    public static final String POST_NOT_LIKED = "POST_NOT_LIKED";
    public static final String POST_ALREADY_REPOSTED = "POST_ALREADY_REPOSTED";

    // Rate limit
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

    // Generic
    public static final String FORBIDDEN = "ACCESS_FORBIDDEN";
    public static final String INTERNAL_ERROR = "INTERNAL_SERVER_ERROR";
    public static final String EXTERNAL_SERVICE_DOWN = "EXTERNAL_SERVICE_UNAVAILABLE";
}
