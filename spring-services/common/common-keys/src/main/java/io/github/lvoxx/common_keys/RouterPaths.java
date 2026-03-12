package io.github.lvoxx.common_keys;

public final class RouterPaths {
    public static class MediaService {
        public static final String UPLOAD = "/api/v1/media/upload";
        public static final String GET_BY_ID = "/api/v1/media/{mediaId}";
        public static final String GET_STATUS = "/api/v1/media/{mediaId}/status";
        public static final String DELETE = "/api/v1/media/{mediaId}";
    }

    public static class UserService {
        public static final String ABOUTME = "/api/v1/users/me";
        public static final String SEARCH = "/api/v1/users/search";
        public static final String HISTORY = "/api/v1/users/me/history";
        public static final String FOLLOW_REQUESTS = "/api/v1/users/me/follow-requests";
        public static final String BY_USERNAME = "/api/v1/users/{username}";
        public static final String FOLLOWERS = "/api/v1/users/{userId}/followers";
        public static final String FOLLOWING = "/api/v1/users/{userId}/following";
        public static final String UPDATE_AVATAR = "/api/v1/users/me/avatar";
        public static final String UPDATE_BACKGROUND = "/api/v1/users/me/background";
        public static final String UPDATE_SETTINGS = "/api/v1/users/me/settings";
        public static final String RESPOND_FOLLOW_REQUEST = "/api/v1/users/me/follow-requests/{reqId}";
        public static final String SUBMIT_VERIFICATION = "/api/v1/users/me/verify";
        public static final String FOLLOW = "/api/v1/users/{userId}/follow";
    }

    public static class PostService {
        public static final String FEED_HOME = "/api/v1/posts/feed/home";
        public static final String FEED_EXPLORE = "/api/v1/posts/feed/explore";
        public static final String USER_POSTS = "/api/v1/users/{userId}/posts";
        public static final String THREAD = "/api/v1/posts/{postId}/thread";
        public static final String POST = "/api/v1/posts/{postId}";
        public static final String CREATE_POST = "/api/v1/posts";
        public static final String LIKE_POST = "/api/v1/posts/{postId}/like";
        public static final String REPOST_POST = "/api/v1/posts/{postId}/repost";
        public static final String BOOKMARK_POST = "/api/v1/posts/{postId}/bookmark";
        public static final String REPORT_POST = "/api/v1/posts/{postId}/report";
    }

    public static class PostInteractionService {
        public static final String LIKE = "/api/v1/posts/{postId}/like";
        public static final String REPOST = "/api/v1/posts/{postId}/repost";
        public static final String VIEW = "/api/v1/posts/{postId}/view";
        public static final String COUNTS = "/api/v1/posts/{postId}/counts";
    }

    public static class CommentService {
        public static final String CREATE_COMMENT = "/api/v1/posts/{postId}/comments";
        public static final String LIST_COMMENTS = "/api/v1/posts/{postId}/comments";
        public static final String DELETE_COMMENT = "/api/v1/comments/{commentId}";
        public static final String REACT_COMMENT = "/api/v1/comments/{commentId}/reactions";
        public static final String COMMENT_COUNT = "/api/v1/posts/{postId}/comments/count";
    }

    public static class NotificationService {
        public static final String LIST = "/api/v1/notifications";
        public static final String MARK_READ = "/api/v1/notifications/{notificationId}/read";
        public static final String MARK_ALL_READ = "/api/v1/notifications/read-all";
        public static final String UNREAD_COUNT = "/api/v1/notifications/unread-count";
    }

    public static class SearchService {
        public static final String SEARCH = "/api/v1/search";
        public static final String TRENDING_HASHTAGS = "/api/v1/search/trending/hashtags";
        public static final String AUTOCOMPLETE = "/api/v1/search/autocomplete";
    }

    public static class GroupService {
        public static final String CREATE_GROUP = "/api/v1/groups";
        public static final String GET_GROUP = "/api/v1/groups/{groupId}";
        public static final String LIST_GROUPS = "/api/v1/groups";
        public static final String JOIN_GROUP = "/api/v1/groups/{groupId}/join";
        public static final String LEAVE_GROUP = "/api/v1/groups/{groupId}/leave";
        public static final String LIST_MEMBERS = "/api/v1/groups/{groupId}/members";
        public static final String JOIN_REQUESTS = "/api/v1/groups/{groupId}/join-requests";
        public static final String RESPOND_JOIN_REQUEST = "/api/v1/groups/{groupId}/join-requests/{requestId}";
    }

    public static class BookmarkService {
        public static final String LIST_COLLECTIONS = "/api/v1/bookmarks/collections";
        public static final String CREATE_COLLECTION = "/api/v1/bookmarks/collections";
        public static final String DELETE_COLLECTION = "/api/v1/bookmarks/collections/{collectionId}";
        public static final String LIST_BOOKMARKS = "/api/v1/bookmarks/collections/{collectionId}/items";
        public static final String ADD_BOOKMARK = "/api/v1/bookmarks/collections/{collectionId}/items";
        public static final String REMOVE_BOOKMARK = "/api/v1/bookmarks/collections/{collectionId}/items/{postId}";
    }

    public static class PrivateMessageService {
        public static final String LIST_CONVERSATIONS = "/api/v1/conversations";
        public static final String CREATE_CONVERSATION = "/api/v1/conversations";
        public static final String GET_CONVERSATION = "/api/v1/conversations/{conversationId}";
        public static final String LIST_MESSAGES = "/api/v1/conversations/{conversationId}/messages";
        public static final String SEND_MESSAGE = "/api/v1/conversations/{conversationId}/messages";
        public static final String DELETE_MESSAGE = "/api/v1/conversations/{conversationId}/messages/{messageId}";
        public static final String EDIT_MESSAGE = "/api/v1/conversations/{conversationId}/messages/{messageId}";
        public static final String REACT_MESSAGE = "/api/v1/conversations/{conversationId}/messages/{messageId}/reactions";
        public static final String MARK_READ = "/api/v1/conversations/{conversationId}/read";
        public static final String WEBSOCKET = "/ws/messages";
    }

    public static class MessageNotificationService {
        public static final String REGISTER_DEVICE = "/api/v1/message-notifications/devices";
        public static final String UPDATE_DEVICE = "/api/v1/message-notifications/devices/{deviceId}";
        public static final String DEREGISTER_DEVICE = "/api/v1/message-notifications/devices/{deviceId}";
        public static final String LIST_DEVICES = "/api/v1/message-notifications/devices";
        public static final String DELIVERY_LOG = "/api/v1/message-notifications/log";
        public static final String TEST_PUSH = "/api/v1/message-notifications/test";
        public static final String STATS = "/api/v1/message-notifications/stats";
    }
}
