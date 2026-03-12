package io.github.lvoxx.common_keys;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that key-factory methods produce deterministic, correctly-formatted
 * strings.
 * These tests protect against accidental regressions in naming conventions that
 * would
 * break Kafka consumers, Redis cache lookups, or distributed locks at runtime.
 */
class CommonKeysTest {

    // ── KafkaTopics ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("KafkaTopics constants")
    class KafkaTopicsTest {

        @Test
        @DisplayName("all topic names follow dot-notation convention")
        void allDotNotation() {
            assertThat(KafkaTopics.User.USER_PROFILE_UPDATED).isEqualTo("user.profile.updated");
            assertThat(KafkaTopics.User.USER_AVATAR_CHANGED).isEqualTo("user.avatar.changed");
            assertThat(KafkaTopics.User.USER_BACKGROUND_CHANGED).isEqualTo("user.background.changed");
            assertThat(KafkaTopics.User.USER_FOLLOWED).isEqualTo("user.followed");
            assertThat(KafkaTopics.User.USER_UNFOLLOWED).isEqualTo("user.unfollowed");

            assertThat(KafkaTopics.Post.POST_CREATED).isEqualTo("post.created");
            assertThat(KafkaTopics.Post.POST_LIKED).isEqualTo("post.liked");
            assertThat(KafkaTopics.Post.POST_REPOSTED).isEqualTo("post.reposted");

            assertThat(KafkaTopics.Comment.COMMENT_CREATED).isEqualTo("comment.created");

            assertThat(KafkaTopics.Group.GROUP_CREATED).isEqualTo("group.created");
            assertThat(KafkaTopics.Group.GROUP_MEMBER_JOINED).isEqualTo("group.member.joined");

            assertThat(KafkaTopics.Media.MEDIA_UPLOAD_COMPLETED).isEqualTo("media.upload.completed");
        }

        @Test
        @DisplayName("no topic name contains uppercase")
        void allLowercase() {
            String[] topics = {
                    KafkaTopics.User.USER_PROFILE_UPDATED, KafkaTopics.User.USER_AVATAR_CHANGED,
                    KafkaTopics.User.USER_BACKGROUND_CHANGED, KafkaTopics.User.USER_FOLLOWED,
                    KafkaTopics.User.USER_UNFOLLOWED, KafkaTopics.Post.POST_CREATED,
                    KafkaTopics.Post.POST_LIKED, KafkaTopics.Post.POST_REPOSTED,
                    KafkaTopics.Comment.COMMENT_CREATED, KafkaTopics.Group.GROUP_CREATED,
                    KafkaTopics.Group.GROUP_MEMBER_JOINED, KafkaTopics.Message.CONVERSATION_CREATED,
                    KafkaTopics.Message.MESSAGE_SENT, KafkaTopics.Media.MEDIA_UPLOAD_COMPLETED
            };
            for (String t : topics) {
                assertThat(t).isEqualTo(t.toLowerCase());
            }
        }
    }

    // ── CacheKeys ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CacheKeys prefixes")
    class CacheKeysTest {

        @Test
        @DisplayName("all prefixes end with ':'")
        void allEndWithColon() {
            assertThat(CacheKeys.UserService.PROFILE).endsWith(":");
            assertThat(CacheKeys.UserService.PROFILE_USERNAME).endsWith(":");
            assertThat(CacheKeys.UserService.FOLLOWER_COUNT).endsWith(":");
            assertThat(CacheKeys.UserService.FOLLOWING_COUNT).endsWith(":");
            assertThat(CacheKeys.Post.POST_DETAIL).endsWith(":");
            assertThat(CacheKeys.Post.POST_HOME_FEED).endsWith(":");
            assertThat(CacheKeys.Group.GROUP_DETAIL).endsWith(":");
            assertThat(CacheKeys.Group.GROUP_MEMBER_COUNT).endsWith(":");
            assertThat(CacheKeys.Notification.NOTIFICATION_UNREAD_COUNT).endsWith(":");
            assertThat(CacheKeys.Search.SEARCH_SUGGESTIONS).endsWith(":");
        }

        @Test
        @DisplayName("prefix + id produces valid Redis key")
        void keyComposition() {
            UUID userId = UUID.randomUUID();
            String key = CacheKeys.UserService.PROFILE + userId;
            assertThat(key).startsWith("user:profile:").contains(userId.toString());
        }

        @Test
        @DisplayName("username prefix differs from id prefix")
        void usernamePrefixDistinct() {
            assertThat(CacheKeys.UserService.PROFILE_USERNAME).isNotEqualTo(CacheKeys.UserService.PROFILE);
            assertThat(CacheKeys.UserService.PROFILE_USERNAME).contains("username");
        }
    }

    // ── LockKeys ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("LockKeys factory methods")
    class LockKeysTest {

        @Test
        @DisplayName("follow key contains both UUIDs")
        void followKeyContainsBothIds() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            String key = LockKeys.follow(a, b);
            assertThat(key).startsWith("lock:follow:").contains(a.toString()).contains(b.toString());
        }

        @Test
        @DisplayName("follow key is directional (A→B ≠ B→A)")
        void followKeyDirectional() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            assertThat(LockKeys.follow(a, b)).isNotEqualTo(LockKeys.follow(b, a));
        }

        @Test
        @DisplayName("postLike key contains userId and postId")
        void postLikeKey() {
            UUID userId = UUID.randomUUID();
            UUID postId = UUID.randomUUID();
            String key = LockKeys.postLike(userId, postId);
            assertThat(key).startsWith("lock:post:like:").contains(userId.toString()).contains(postId.toString());
        }

        @Test
        @DisplayName("groupJoin key contains userId and groupId")
        void groupJoinKey() {
            UUID userId = UUID.randomUUID();
            UUID groupId = UUID.randomUUID();
            String key = LockKeys.groupJoin(userId, groupId);
            assertThat(key).startsWith("lock:group:join:").contains(userId.toString()).contains(groupId.toString());
        }

        @Test
        @DisplayName("directConversation key is symmetric: (A,B) == (B,A)")
        void directConvSymmetric() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            assertThat(LockKeys.directConversation(a, b))
                    .isEqualTo(LockKeys.directConversation(b, a));
        }

        @Test
        @DisplayName("directConversation key contains both UUIDs")
        void directConvContainsBothIds() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            String key = LockKeys.directConversation(a, b);
            assertThat(key).contains(a.toString()).contains(b.toString());
        }

        @Test
        @DisplayName("userProfileUpdate key contains userId")
        void profileUpdateKey() {
            UUID userId = UUID.randomUUID();
            assertThat(LockKeys.userProfileUpdate(userId))
                    .startsWith("lock:user:profile:update:")
                    .contains(userId.toString());
        }

        @Test
        @DisplayName("all lock keys start with 'lock:'")
        void allStartWithLock() {
            UUID id = UUID.randomUUID();
            assertThat(LockKeys.follow(id, id)).startsWith("lock:");
            assertThat(LockKeys.postLike(id, id)).startsWith("lock:");
            assertThat(LockKeys.groupJoin(id, id)).startsWith("lock:");
            assertThat(LockKeys.groupMemberCount(id)).startsWith("lock:");
            assertThat(LockKeys.directConversation(id, id)).startsWith("lock:");
            assertThat(LockKeys.userProfileUpdate(id)).startsWith("lock:");
        }
    }

    // ── DeepLinkPaths ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DeepLinkPaths factory methods")
    class DeepLinkPathsTest {

        @Test
        @DisplayName("post path contains postId")
        void postPath() {
            UUID id = UUID.randomUUID();
            assertThat(DeepLinkPaths.post(id)).isEqualTo("/posts/" + id);
        }

        @Test
        @DisplayName("userProfile path contains userId")
        void userProfilePath() {
            UUID id = UUID.randomUUID();
            assertThat(DeepLinkPaths.userProfile(id)).isEqualTo("/users/" + id);
        }

        @Test
        @DisplayName("postComments path contains postId and /comments suffix")
        void postCommentsPath() {
            UUID id = UUID.randomUUID();
            assertThat(DeepLinkPaths.postComments(id)).isEqualTo("/posts/" + id + "/comments");
        }

        @Test
        @DisplayName("group path contains groupId")
        void groupPath() {
            UUID id = UUID.randomUUID();
            assertThat(DeepLinkPaths.group(id)).isEqualTo("/groups/" + id);
        }

        @Test
        @DisplayName("conversation path contains convId")
        void conversationPath() {
            UUID id = UUID.randomUUID();
            assertThat(DeepLinkPaths.conversation(id))
                    .isEqualTo("/messages/conversations/" + id);
        }

        @Test
        @DisplayName("all paths start with '/'")
        void allStartWithSlash() {
            UUID id = UUID.randomUUID();
            assertThat(DeepLinkPaths.post(id)).startsWith("/");
            assertThat(DeepLinkPaths.userProfile(id)).startsWith("/");
            assertThat(DeepLinkPaths.postComments(id)).startsWith("/");
            assertThat(DeepLinkPaths.group(id)).startsWith("/");
            assertThat(DeepLinkPaths.groupJoinRequests(id)).startsWith("/");
            assertThat(DeepLinkPaths.conversation(id)).startsWith("/");
        }
    }
}
