# message-notification-service

**Type:** Spring Boot · Port `8089`  
**Primary DB:** Apache Cassandra — keyspace `sssm_msg_notifications`  
**Cache:** Redis  
**Push channels:** FCM · APNs · Web Push (VAPID)  
**Starters:** `cassandra-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter`

---

## Responsibilities

Push notification delivery for private messages. Separated from `notification-service` because message notifications need multi-channel push (FCM/APNs/Web Push), message-preview masking, sliding-window batching, `allowDmFrom` filtering, and device-token lifecycle management.

---

## DB init

K8S `InitContainer` runs `cqlsh`.  
Scripts: `infrastructure/k8s/db-init/message-notification-service/cql/`  
`spring.cassandra.schema-action: NONE`

---

## Schema (keyspace `sssm_msg_notifications`)

```cql
CREATE TABLE device_push_tokens (
  user_id       UUID,
  device_id     UUID,
  platform      TEXT,          -- FCM_ANDROID|APNS_IOS|WEB_PUSH
  token         TEXT,
  app_version   TEXT,
  registered_at TIMESTAMP,
  last_active   TIMESTAMP,
  is_active     BOOLEAN DEFAULT TRUE,
  PRIMARY KEY (user_id, device_id)
);

-- Delivery audit log, TTL 30 days, append-only
CREATE TABLE message_notification_log (
  user_id          UUID,
  notification_id  TIMEUUID,
  conversation_id  UUID,
  message_id       UUID,
  channel          TEXT,        -- FCM_ANDROID|APNS_IOS|WEB_PUSH|SKIPPED
  status           TEXT,        -- PENDING|SENT|FAILED|SKIPPED
  sent_at          TIMESTAMP,
  failure_reason   TEXT,
  retry_count      INT DEFAULT 0,
  PRIMARY KEY (user_id, notification_id)
) WITH CLUSTERING ORDER BY (notification_id DESC)
  AND default_time_to_live = 2592000;
```

---

## Push decision flow

```
Kafka: message.sent received
  │
  ├─ Load participants from Redis (cached from private-message-svc)
  │
  └─ Per participant (excluding sender):
       │
       ├ [1] WS online? (Redis key msg:notif:ws-online:{userId} TTL 30s)
       │     → YES: skip (WS delivery already handled)
       │
       ├ [2] allowDmFrom check (user-level setting, Redis cache)
       │     → sender not allowed: SKIP + log status=SKIPPED
       │
       ├ [3] Per-conv settings (Redis cache)
       │     muteUntil / mutedForever → SKIP
       │     notifyOn = NONE          → SKIP
       │     notifyOn = MENTIONS_ONLY + not mentioned → SKIP
       │
       ├ [4] Batching window (Redis LPUSH, EXPIRE 5s)
       │     > 1 msg in window → accumulate; flush after window closes
       │
       ├ [5] Build payload
       │     messagePreview=true  → "UserA: {first 80 chars}"
       │     messagePreview=false → "New message from UserA"
       │     GROUP_CHAT           → "GroupName: UserA sent a message"
       │
       └ [6] Dispatch in parallel to all is_active device tokens
             FCM   → Firebase HTTP v1 API
             APNs  → Apple HTTP/2 + JWT
             Web Push → VAPID RFC 8291/8292
             Log result → Cassandra message_notification_log
```

---

## Batching algorithm

```
Key: msg:notif:batch:{userId}:{convId}
  LPUSH key "{preview}"  →  EXPIRE key 5

On 5-second window close (Reactor interval):
  items = LRANGE key 0 -1;  DEL key
  count == 1  → normal push: "UserA: Hello!"
  count  > 1  → batch push:  "3 new messages from UserA"
```

---

## Stale token handling

```
FCM response "NOT_REGISTERED"  → UPDATE device_push_tokens SET is_active=false
APNs 410 "Unregistered"        → UPDATE device_push_tokens SET is_active=false
Web Push 404 / 410             → UPDATE device_push_tokens SET is_active=false
```

---

## application.yaml additions

```yaml
sssm:
  message-notification:
    fcm:
      service-account-key: ${FCM_SERVICE_ACCOUNT_KEY}
      project-id:          ${FCM_PROJECT_ID}
    apns:
      key-id:           ${APNS_KEY_ID}
      team-id:          ${APNS_TEAM_ID}
      private-key-path: ${APNS_PRIVATE_KEY_PATH}
      bundle-id:        ${APNS_BUNDLE_ID}
      sandbox:          ${APNS_SANDBOX:false}
    web-push:
      vapid-public-key:  ${VAPID_PUBLIC_KEY}
      vapid-private-key: ${VAPID_PRIVATE_KEY}
      subject:           mailto:push@sssm.com
    batching:
      window-seconds: 5
      max-batch-size: 20
    retry:
      max-attempts: 3
      backoff-multiplier: 2.0
      initial-interval-ms: 500
```

---

## Kafka

### Consumed

| Topic | Action |
|-------|--------|
| `message.sent` | Main trigger — evaluate + dispatch |
| `message.reaction.added` | Notify message owner |
| `conversation.created` | Notify invited participants |
| `conversation.settings.updated` | Invalidate settings cache |

### Published

| Topic | Consumers |
|-------|-----------|
| `message.notification.delivered` | private-message-svc (delivery log) |
| `message.notification.failed` | ai-dashboard-svc |

---

## API

```
POST   /api/v1/message-notifications/devices
PUT    /api/v1/message-notifications/devices/{deviceId}
DELETE /api/v1/message-notifications/devices/{deviceId}
GET    /api/v1/message-notifications/devices

GET    /api/v1/message-notifications/log?page=&size=&convId=&status=

POST   /api/v1/message-notifications/test        # dev / staging only
GET    /api/v1/message-notifications/stats       # ADMIN role
```

---

## Cache keys

| Key | TTL |
|-----|-----|
| `msg:notif:settings:{userId}` | 5 min |
| `msg:notif:conv-settings:{userId}:{convId}` | 5 min |
| `msg:notif:token:{userId}` | 24 h |
| `msg:notif:batch:{userId}:{convId}` | 5 s (batching window) |
| `msg:notif:ws-online:{userId}` | 30 s |

---

## Tests

- **Unit:** `NotificationDecisionServiceTest`, `BatchingServiceTest`, `FcmDispatcherTest`, `ApnsDispatcherTest`, `WebPushDispatcherTest`
- **Integration:** Cassandra + Redis + Kafka + WireMock (FCM / APNs)
- **Automation:** 5 rapid messages → verify single batched push · device register → deregister · stale token cleanup
