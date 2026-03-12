# common-keys

Zero-dependency module that centralises every string constant shared across the platform:
Kafka topic names, Redis cache-key prefixes, distributed-lock key factories, and deep-link paths.

Importing this module eliminates scattered string literals and makes cross-service contracts explicit and refactor-safe.

---

## Contents

### `KafkaTopics`

All Kafka topic name constants in `<domain>.<action>` dot-notation.

```java
KafkaTopics.POST_CREATED           // "post.created"
KafkaTopics.USER_FOLLOWED          // "user.followed"
KafkaTopics.GROUP_MEMBER_JOINED    // "group.member.joined"
KafkaTopics.MESSAGE_SENT           // "message.sent"
KafkaTopics.MEDIA_UPLOAD_COMPLETED // "media.upload.completed"
// ... 15 topics total
```

Use in `@KafkaListener` via SpEL:
```java
@KafkaListener(topics = "#{T(io.github.lvoxx.common.keys.KafkaTopics).POST_CREATED}")
```

### `CacheKeys`

Redis key prefixes (all end with `:` for easy ID appending).

```java
String key = CacheKeys.USER_PROFILE + userId;           // "user:profile:<uuid>"
String key = CacheKeys.USER_PROFILE_USERNAME + username; // "user:profile:username:alice"
String key = CacheKeys.POST_DETAIL + postId;            // "post:detail:<uuid>"
String key = CacheKeys.SEARCH_TRENDING_HASHTAGS;        // "search:trending:hashtags" (no suffix needed)
```

### `LockKeys`

Factory methods for distributed lock keys (used with `LockService`).

```java
// Prevents concurrent follow races between the same pair
String key = LockKeys.follow(followerId, targetId);

// Symmetric — (A,B) and (B,A) produce the same key (canonical ordering)
String key = LockKeys.directConversation(userA, userB);

// Other factories
LockKeys.postLike(userId, postId)
LockKeys.groupJoin(userId, groupId)
LockKeys.groupMemberCount(groupId)
LockKeys.userProfileUpdate(userId)
```

### `DeepLinkPaths`

URL builders for notification deep-links.

```java
DeepLinkPaths.post(postId)            // "/posts/<uuid>"
DeepLinkPaths.userProfile(userId)     // "/users/<uuid>"
DeepLinkPaths.postComments(postId)    // "/posts/<uuid>/comments"
DeepLinkPaths.group(groupId)          // "/groups/<uuid>"
DeepLinkPaths.conversation(convId)    // "/messages/conversations/<uuid>"
```

---

## Usage

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>common-keys</artifactId>
</dependency>
```

```java
// In a Kafka producer
kafka.send(KafkaTopics.USER_FOLLOWED, key, event);

// In a LockService call
lockService.withLock(LockKeys.follow(followerId, targetId), () -> ...)
    .switchIfEmpty(Mono.error(new ConflictException(...)));

// In a notification consumer
notification.setDeepLink(DeepLinkPaths.post(event.getPostId()));
```

---

## Design Constraints

- **No runtime dependencies** — pure Java constants, safe to import anywhere.
- `LockKeys` factory methods take `Object` parameters so callers need not convert UUIDs to strings.
- `directConversation` applies canonical ordering so `(A,B)` and `(B,A)` always return the same Redis key.
