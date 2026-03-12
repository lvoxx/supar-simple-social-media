# private-message-service

**Type:** Spring Boot · Port `8088`  
**Primary DB:** Apache Cassandra — keyspace `sssm_messages`  
**Cache:** Redis (metadata cache + Pub/Sub for cross-pod WS routing)  
**Starters:** `cassandra-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter` `websocket-starter` `grpc-starter`

---

## Responsibilities

End-to-end private messaging: 1-on-1 DMs, user-created group chats (up to 500 members), and group-channel conversations linked to social groups. Handles real-time WebSocket delivery, reactions, forwarding, read receipts, typing indicators, per-conversation notification settings, and media attachments.

---

## Conversation types

| Type | Max members | Created by |
|------|:-----------:|-----------|
| `DIRECT` | 2 | Either user |
| `GROUP_CHAT` | 500 | Any user |
| `GROUP_CHANNEL` | Unlimited* | group-service (auto) |

\* Bounded by group membership.

---

## Real-time architecture

```
Sender (WS pod-1)
  │ send message
  ▼
private-message-service (pod-1):
  1. Write to Cassandra
  2. PUBLISH Redis channel "conv:{convId}"   ← cross-pod routing
  3. Publish Kafka: message.sent             ← offline push

Redis Pub/Sub fanout:
  pod-1 → deliver to sender (confirmation)
  pod-2 → deliver to recipient B (online)
  pod-3 → deliver to recipient C (online)

Kafka message.sent → message-notification-service → FCM / APNs / Web Push
```

---

---

## gRPC cross-service calls

This service acts as both a **gRPC server** (exposes `ConversationService`) and a **gRPC client** (calls `UserService` and `GroupService`).

### gRPC server — ConversationService

Implements `ConversationService` from `message/conversation_service.proto`.

```java
@GrpcService
@RequiredArgsConstructor
public class ConversationGrpcService extends ReactorConversationServiceGrpc.ConversationServiceImplBase {

    private final ConversationRepository conversationRepo;
    private final ParticipantRepository  participantRepo;

    @Override
    public Mono<ConversationResponse> findConversationById(Mono<FindConversationByIdRequest> request) {
        return request.flatMap(req ->
            conversationRepo.findById(req.getConversationId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException("Conversation: " + req.getConversationId())))
                .map(this::toProto)
        );
    }

    @Override
    public Mono<ParticipantListResponse> listParticipants(Mono<ListParticipantsRequest> request) {
        return request.flatMap(req ->
            participantRepo.findByConversationId(req.getConversationId())
                .map(p -> Participant.newBuilder()
                    .setUserId(p.getUserId())
                    .setRole(p.getRole())
                    .setStatus(p.getStatus())
                    .build())
                .collectList()
                .map(list -> ParticipantListResponse.newBuilder().addAllParticipants(list).build())
        );
    }

    @Override
    public Mono<ConversationResponse> findByGroupId(Mono<FindByGroupIdRequest> request) {
        return request.flatMap(req ->
            conversationRepo.findByGroupId(req.getGroupId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException("Conversation for group: " + req.getGroupId())))
                .map(this::toProto)
        );
    }
}
```

### gRPC clients

```java
@Service
@RequiredArgsConstructor
public class ConversationService {

    @GrpcClient("user-service")
    private ReactorUserServiceGrpc.ReactorUserServiceStub userStub;

    @GrpcClient("group-service")
    private ReactorGroupServiceGrpc.ReactorGroupServiceStub groupStub;

    // Block check before creating a DM
    public Mono<Void> checkNotBlocked(String requesterId, String targetId) {
        return userStub
            .withDeadlineAfter(3, TimeUnit.SECONDS)
            .checkUserBlocked(CheckUserBlockedRequest.newBuilder()
                .setBlockerId(targetId)
                .setBlockedId(requesterId)
                .build())
            .flatMap(resp -> resp.getIsBlocked()
                ? Mono.error(new ForbiddenException("User has blocked you"))
                : Mono.<Void>empty()
            );
    }

    // Seed GROUP_CHANNEL participants when creating a new group channel
    public Mono<List<String>> fetchGroupMemberIds(String groupId) {
        return groupStub
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .listGroupMembers(ListGroupMembersRequest.newBuilder()
                .setGroupId(groupId)
                .build())
            .map(resp -> resp.getMembersList().stream()
                .map(GroupMember::getUserId)
                .toList()
            );
    }

    // Read receipt privacy — skip broadcast if user turned it off
    public Mono<Boolean> isReadReceiptEnabled(String userId) {
        return userStub
            .withDeadlineAfter(3, TimeUnit.SECONDS)
            .getUserSettings(GetUserSettingsRequest.newBuilder().setUserId(userId).build())
            .map(UserSettingsResponse::getReadReceipts);
    }
}
```

### application.yaml (gRPC client config)

```yaml
grpc:
  server:
    port: 9090
  client:
    user-service:
      address:          static://user-service.sssm.svc.cluster.local:9090
      negotiation-type: plaintext
      deadline-duration: 3s
    group-service:
      address:          static://group-service.sssm.svc.cluster.local:9090
      negotiation-type: plaintext
      deadline-duration: 5s
```


## Typing indicator — "User is typing"

### Design principles

- **Ephemeral** — typing state is never written to Cassandra. It lives only in Redis with a short TTL.
- **Cross-pod** — Redis Pub/Sub fans out typing events across all service pods.
- **Debounced on client** — client sends `TYPING_START` once, then re-sends every 3 s while still typing. Sends `TYPING_STOP` on blur, send, or cancel.
- **Server-side TTL** — if no `TYPING_START` arrives for 5 s, the server auto-evicts the Redis key and broadcasts a synthetic `TYPING_STOP`.

### Redis data model

```
Key   : typing:{conversationId}:{userId}
Value : "1"
TTL   : 5 seconds (refreshed on every TYPING_START)
```

```
Channel (Pub/Sub): typing:{conversationId}
Payload           : { "userId": "01HXZ...", "typing": true|false }
```

### Server flow

```
Client sends WS frame: TYPING_START { conversationId }
  │
  ▼
TypingIndicatorHandler:
  1. Validate: user is participant of conversation (check Redis cache)
  2. Check current Redis key exists (typing:{convId}:{userId})
     → exists (already known to be typing) → skip Pub/Sub re-publish  [debounce]
     → does not exist (newly typing)       → continue
  3. SET typing:{convId}:{userId} "1" EX 5   (Redis)
  4. PUBLISH typing:{convId}  {"userId": "01HXZ...", "typing": true}  (Redis Pub/Sub)

Redis Pub/Sub subscriber (all pods receive):
  5. For each online participant (from local WS session map):
     → push WS frame: TYPING_INDICATOR { conversationId, userId, typing: true }

TTL expiry watcher (Keyspace notification, Redis __keyevent@*__:expired):
  6. On key expiry event for "typing:{convId}:{userId}":
     → PUBLISH typing:{convId}  {"userId": "01HXZ...", "typing": false}
     → Each pod pushes TYPING_INDICATOR { ..., typing: false } to online participants

Client sends WS frame: TYPING_STOP { conversationId }  (explicit stop)
  → DEL typing:{convId}:{userId}
  → PUBLISH typing:{convId}  {"userId": "01HXZ...", "typing": false}
```

### Implementation

```java
// TypingIndicatorHandler.java

@Component
@RequiredArgsConstructor
public class TypingIndicatorHandler {

    private static final Duration TYPING_TTL       = Duration.ofSeconds(5);
    private static final Duration DEBOUNCE_WINDOW  = Duration.ofSeconds(4);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ReactiveRedisMessageListenerContainer redisListener;
    private final ParticipantCacheService participantCache;

    public Mono<Void> handleTypingStart(String conversationId, String userId) {
        String redisKey = typingKey(conversationId, userId);
        String channel  = typingChannel(conversationId);
        String payload  = toJson(new TypingPayload(userId, true));

        return participantCache.isParticipant(conversationId, userId)
            .filter(Boolean.TRUE::equals)
            .flatMap(__ ->
                // SETNX-like: SET key "1" EX 5
                // If key already existed (still in debounce window), skip Pub/Sub publish
                redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", TYPING_TTL)
                    .flatMap(isNew -> {
                        if (Boolean.TRUE.equals(isNew)) {
                            // First TYPING_START — publish to all pods
                            return redisTemplate.convertAndSend(channel, payload).then();
                        }
                        // Still within debounce window — just refresh TTL
                        return redisTemplate.expire(redisKey, TYPING_TTL).then();
                    })
            );
    }

    public Mono<Void> handleTypingStop(String conversationId, String userId) {
        String redisKey = typingKey(conversationId, userId);
        String channel  = typingChannel(conversationId);
        String payload  = toJson(new TypingPayload(userId, false));

        return redisTemplate.delete(redisKey)
            .filter(deleted -> deleted > 0)   // only publish if key existed
            .flatMap(__ -> redisTemplate.convertAndSend(channel, payload).then());
    }

    // Called once per pod on startup — subscribe to all typing channels
    public void startTypingBroadcast(WebSocketSessionRegistry sessionRegistry) {
        PatternTopic pattern = new PatternTopic("typing:*");

        redisListener.receive(pattern)
            .map(msg -> {
                // Channel: "typing:{conversationId}"
                String convId   = msg.getChannel().substring("typing:".length());
                TypingPayload p = fromJson(msg.getMessage(), TypingPayload.class);
                return new TypingBroadcastEvent(convId, p.userId(), p.typing());
            })
            .flatMap(event ->
                // Push to all locally connected participants of this conversation
                sessionRegistry.getSessions(event.conversationId())
                    .filter(s -> !s.userId().equals(event.userId()))  // don't echo to typer
                    .flatMap(session -> session.send(WsFrame.typingIndicator(
                        event.conversationId(), event.userId(), event.typing()
                    )))
            )
            .subscribe();
    }

    // TTL expiry handler — Redis keyspace notification: __keyevent@*__:expired
    // Requires Redis config: notify-keyspace-events "KEx" (Keyspace + Expired)
    public void startExpiryWatcher() {
        PatternTopic expiredPattern = new PatternTopic("__keyevent@*__:expired");

        redisListener.receive(expiredPattern)
            .filter(msg -> msg.getMessage().startsWith("typing:"))
            .map(msg -> {
                // Key format: typing:{conversationId}:{userId}
                String[] parts = msg.getMessage().split(":");
                return new KeyExpiredEvent(parts[1], parts[2]);  // convId, userId
            })
            .flatMap(event -> {
                String channel = typingChannel(event.conversationId());
                String payload = toJson(new TypingPayload(event.userId(), false));
                return redisTemplate.convertAndSend(channel, payload).then();
            })
            .subscribe();
    }

    private static String typingKey(String convId, String userId) {
        return "typing:" + convId + ":" + userId;
    }
    private static String typingChannel(String convId) {
        return "typing:" + convId;
    }
}
```

### WebSocket handler integration

```java
// PrivateMessageWebSocketHandler.java  (excerpt)

private Mono<Void> handleFrame(WebSocketSession session, WsFrame frame, UserPrincipal user) {
    return switch (frame.type()) {
        case JOIN_CONVERSATION  -> handleJoin(session, frame.conversationId(), user);
        case LEAVE_CONVERSATION -> handleLeave(session, frame.conversationId(), user);
        case TYPING_START       -> typingHandler.handleTypingStart(frame.conversationId(), user.userId());
        case TYPING_STOP        -> typingHandler.handleTypingStop(frame.conversationId(), user.userId());
        case PING               -> session.send(Mono.just(WsFrame.pong()));
        default -> Mono.error(new UnknownFrameTypeException(frame.type()));
    };
}
```

### Redis configuration for keyspace notifications

```yaml
# application.yaml — add to redis-starter config for private-message-service only

spring:
  data:
    redis:
      # Additional config applied via Lettuce client — notify-keyspace-events must be
      # set on the Redis server side (or via CONFIG SET on startup).
      # Set in K8S ConfigMap for Redis:
      #   CONFIG SET notify-keyspace-events KEx
```

```java
// TypingRedisConfig.java — enable keyspace notifications on application startup
@Component
@RequiredArgsConstructor
public class TypingRedisConfig implements ApplicationListener<ApplicationReadyEvent> {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        redisTemplate.execute(connection ->
            connection.serverCommands()
                .setConfig("notify-keyspace-events", "KEx")
        ).subscribe();
    }
}
```

### Typing state — "who is typing" query

```
GET /api/v1/messages/conversations/{convId}/typing

Response: { "typingUsers": ["01HXZ...", "01HXY..."] }
```

```java
// Uses Redis SCAN to find all active typing keys for a conversation
public Mono<List<String>> getTypingUsers(String conversationId) {
    ScanOptions options = ScanOptions.scanOptions()
        .match("typing:" + conversationId + ":*")
        .count(100)
        .build();
    return redisTemplate.scan(options)
        .map(key -> key.substring(("typing:" + conversationId + ":").length()))
        .collectList();
}
```

---

## DB init

K8S `InitContainer` runs `cqlsh`.  
Scripts: `infrastructure/k8s/db-init/private-message-service/cql/`  
`spring.cassandra.schema-action: NONE`

---

## Schema (keyspace `sssm_messages`)

```cql
CREATE TABLE conversations (
  conversation_id UUID       PRIMARY KEY,
  type            TEXT,                      -- DIRECT|GROUP_CHAT|GROUP_CHANNEL
  name            TEXT,
  avatar_url      TEXT,
  group_id        UUID,
  created_by      UUID,
  created_at      TIMESTAMP,
  updated_at      TIMESTAMP,
  settings        TEXT,                      -- JSON global conversation settings
  is_deleted      BOOLEAN DEFAULT FALSE
);

CREATE TABLE conversation_participants (
  conversation_id       UUID,
  user_id               UUID,
  role                  TEXT DEFAULT 'MEMBER',  -- OWNER|ADMIN|MEMBER
  status                TEXT DEFAULT 'ACTIVE',  -- ACTIVE|LEFT|REMOVED|MUTED
  joined_at             TIMESTAMP,
  last_read_message_id  UUID,
  last_read_at          TIMESTAMP,
  notification_settings TEXT,                   -- JSON per-conv settings
  PRIMARY KEY (conversation_id, user_id)
);

-- Reverse lookup: all conversations for a user, sorted by latest message
CREATE TABLE conversations_by_user (
  user_id           UUID,
  last_message_at   TIMESTAMP,
  conversation_id   UUID,
  conversation_type TEXT,
  unread_count      INT,
  is_muted          BOOLEAN,
  PRIMARY KEY (user_id, last_message_at, conversation_id)
) WITH CLUSTERING ORDER BY (last_message_at DESC, conversation_id ASC);

CREATE TABLE messages (
  conversation_id                UUID,
  message_id                     TIMEUUID,
  sender_id                      UUID,
  message_type                   TEXT,    -- TEXT|IMAGE|VIDEO|AUDIO|FILE|STICKER|FORWARDED|SYSTEM
  content                        TEXT,
  media_ids                      LIST<UUID>,
  forwarded_from_message_id      UUID,
  forwarded_from_conversation_id UUID,
  reply_to_message_id            UUID,
  status                         TEXT,    -- SENT|DELIVERED|READ|FAILED|DELETED
  is_deleted                     BOOLEAN DEFAULT FALSE,
  deleted_at                     TIMESTAMP,
  deleted_by                     UUID,
  edited_at                      TIMESTAMP,
  metadata                       TEXT,    -- JSON: {fileName, fileSize, duration}
  created_at                     TIMESTAMP,
  PRIMARY KEY (conversation_id, message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);

CREATE TABLE message_reactions (
  conversation_id UUID,
  message_id      TIMEUUID,
  user_id         UUID,
  emoji           TEXT,
  reacted_at      TIMESTAMP,
  PRIMARY KEY (conversation_id, message_id, user_id)
);

CREATE TABLE message_read_receipts (
  conversation_id UUID,
  message_id      TIMEUUID,
  user_id         UUID,
  read_at         TIMESTAMP,
  PRIMARY KEY (conversation_id, message_id, user_id)
);
```

---

## Business rules

| Rule | Detail |
|------|--------|
| Reaction | 1 per user per message (PUT semantics). Changing emoji overwrites. Physical delete allowed (not user-generated content). |
| Forward | Creates new `FORWARDED` message in target conv. Original is NOT copied — reference only. If source deleted → show `[Message unavailable]`. |
| Edit | `TEXT` type only, within 15 min of send, sender only. Overwrites content, sets `edited_at`. |
| Soft delete | `is_deleted=true`, content replaced by `[Message deleted]` in responses. |
| Read receipt privacy | If `user.settings.readReceipts=false` → Cassandra still updated, but `READ_RECEIPT` WS event NOT broadcast. |

---

## Per-conversation settings (JSON in `notification_settings` column)

```json
{
  "muteUntil":      "2026-06-01T00:00:00Z",
  "mutedForever":   false,
  "notifyOn":       "ALL_MESSAGES",
  "theme":          "BLUE",
  "nickname":       "Bob",
  "messagePreview": true
}
```

---

## WebSocket protocol

```
Connect: WS /ws/messages

Client → Server:
  JOIN_CONVERSATION  / LEAVE_CONVERSATION  { conversationId }
  TYPING_START       / TYPING_STOP         { conversationId }
  PING

Server → Client:
  NEW_MESSAGE         { conversationId, message }
  MESSAGE_UPDATED     { conversationId, messageId, content }
  MESSAGE_DELETED     { conversationId, messageId }
  MESSAGE_REACTION    { conversationId, messageId, emoji, userId, action: ADD|REMOVE }
  READ_RECEIPT        { conversationId, userId, lastReadMessageId }
  TYPING_INDICATOR    { conversationId, userId, typing: bool }
  PARTICIPANT_JOINED  { conversationId, userId }
  PARTICIPANT_LEFT    { conversationId, userId }
  PONG
```

---

## Kafka

### Published

| Topic | Consumers |
|-------|-----------|
| `message.sent` | message-notification-svc, user-analysis-svc |
| `message.delivered` | (multi-device sync) |
| `message.read` | (multi-device sync) |
| `message.reaction.added` | message-notification-svc |
| `message.reaction.removed` | (internal) |
| `message.deleted` | (internal) |
| `conversation.created` | notification-svc |
| `conversation.settings.updated` | message-notification-svc |

### Consumed

| Topic | Action |
|-------|--------|
| `user.profile.updated` | Invalidate participant name/avatar cache |
| `group.member.left` | Remove from GROUP_CHANNEL participants |
| `group.deleted` | Archive GROUP_CHANNEL conversation |

---

## API

```
POST   /api/v1/messages/conversations
GET    /api/v1/messages/conversations
GET    /api/v1/messages/conversations/{convId}
PUT    /api/v1/messages/conversations/{convId}
DELETE /api/v1/messages/conversations/{convId}
POST   /api/v1/messages/conversations/{convId}/mute
POST   /api/v1/messages/conversations/{convId}/settings

GET    /api/v1/messages/conversations/{convId}/participants
POST   /api/v1/messages/conversations/{convId}/participants
DELETE /api/v1/messages/conversations/{convId}/participants/{userId}
PUT    /api/v1/messages/conversations/{convId}/participants/{userId}/role

POST   /api/v1/messages/conversations/{convId}/messages
GET    /api/v1/messages/conversations/{convId}/messages
PUT    /api/v1/messages/conversations/{convId}/messages/{msgId}
DELETE /api/v1/messages/conversations/{convId}/messages/{msgId}
POST   /api/v1/messages/conversations/{convId}/messages/{msgId}/forward
POST   /api/v1/messages/conversations/{convId}/messages/{msgId}/react
DELETE /api/v1/messages/conversations/{convId}/messages/{msgId}/react
POST   /api/v1/messages/conversations/{convId}/messages/read
GET    /api/v1/messages/conversations/{convId}/messages/{msgId}/reactions

WS     /ws/messages
```

---

## Cache keys

| Key | TTL |
|-----|-----|
| `msg:conv:{convId}` | 5 min |
| `msg:participants:{convId}` | 5 min |
| `msg:unread:{userId}:{convId}` | 1 min |
| `msg:conv-list:{userId}:page:0` | 30 s |
| `msg:settings:user:{userId}` | 5 min |
| `msg:settings:conv:{userId}:{convId}` | 5 min |

---

## Tests

- **Unit:** `ConversationServiceTest`, `MessageServiceTest`, `ReactionServiceTest`, `ForwardServiceTest`
- **Integration:** Cassandra + Redis + Kafka containers
- **Automation:** create DM → send → react → forward → delete → read receipt → group chat flow
