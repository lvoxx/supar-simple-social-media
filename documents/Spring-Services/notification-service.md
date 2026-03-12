# notification-service

**Type:** Spring Boot · Port `8085`  
**Primary DB:** Apache Cassandra — keyspace `sssm_notifications`  
**Cache:** Redis (Pub/Sub for cross-pod WS routing)  
**Starters:** `cassandra-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter` `websocket-starter`

---

## Responsibilities

Fan-out in-app notifications to users. Real-time delivery via reactive WebSocket. Multi-device read-state sync via Redis Pub/Sub across pods.

---

## DB init

K8S `InitContainer` runs `cqlsh`.  
Scripts: `infrastructure/k8s/db-init/notification-service/cql/`  
`spring.cassandra.schema-action: NONE`

---

## Schema (keyspace `sssm_notifications`)

```cql
CREATE TABLE notifications_by_user (
  user_id          UUID,
  notification_id  TIMEUUID,
  type             TEXT,
  actor_id         UUID,
  actor_username   TEXT,
  actor_avatar_url TEXT,
  entity_type      TEXT,
  entity_id        UUID,
  message          TEXT,
  deep_link        TEXT,
  is_read          BOOLEAN   DEFAULT FALSE,
  is_deleted       BOOLEAN   DEFAULT FALSE,
  delivered_at     TIMESTAMP,
  created_at       TIMESTAMP,
  PRIMARY KEY (user_id, notification_id)
) WITH CLUSTERING ORDER BY (notification_id DESC)
  AND default_time_to_live = 7776000;    -- 90 days TTL

CREATE TABLE device_sessions (
  user_id     UUID,
  device_id   UUID,
  pod_id      TEXT,         -- K8S pod for WS routing
  platform    TEXT,
  last_active TIMESTAMP,
  PRIMARY KEY (user_id, device_id)
);
```

---

## WebSocket protocol

```
Connect: WS /ws/notifications

Server → Client:
  NOTIFICATION        { id, type, message, deepLink, actor, timestamp }
  READ_STATE_UPDATE   { notificationIds }
  UNREAD_COUNT_UPDATE { count }

Client → Server:
  PING
  MARK_READ { notificationId }
```

**Multi-device read sync:**
```
Device A marks read → Cassandra is_read=true
→ Publish Kafka: notification.read { userId, notificationId }
→ consumer pushes READ_STATE_UPDATE to all other user sessions via Redis Pub/Sub
```

---

## Kafka consumed

`post.liked` · `post.reposted` · `post.created` · `comment.created` · `comment.liked` · `user.followed` · `user.verified` · `media.upload.completed` · `notification.read` · `group.member.joined` · `group.post.pinned` · `conversation.created`

Kafka: `user.preferences.updated` → update per-user notification delivery rules (FCM token, push enabled)

---

## API

```
GET    /api/v1/notifications
POST   /api/v1/notifications/read-all
PUT    /api/v1/notifications/{id}/read
GET    /api/v1/notifications/unread-count
DELETE /api/v1/notifications/{id}
WS     /ws/notifications
```

---

## Cache keys

| Key | TTL |
|-----|-----|
| `notif:unread:{userId}` | 5 min |
| `notif:settings:{userId}` | 10 min |
| `notif:ws-sessions:{userId}` | 30 s |

---

## Tests

- **Unit:** `NotificationFanoutServiceTest`, `UserPreferenceFilterTest`
- **Integration:** Cassandra + Redis + Kafka containers
- **Automation:** Kafka event → notification stored → WS push → mark read → multi-device sync

---
---

# search-service

**Type:** Spring Boot · Port `8086`  
**Primary DB:** Elasticsearch 8 — dedicated cluster  
**Cache:** Redis  
**Starters:** `elasticsearch-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter`

---

## Responsibilities

Full-text search (posts, users, hashtags, groups), autocomplete, trending hashtags. Maintains denormalised search indices fed by Kafka CDC events. **Owns only Elasticsearch — no PostgreSQL/Cassandra schema.**

---

## DB init

K8S `Job` calls ES REST API to create indices.  
Mapping files: `infrastructure/k8s/db-init/search-service/mappings/`

```
users_v1.json
posts_v1.json
hashtags_v1.json
groups_v1.json
```

---

## Index overview

| Index | Refresh | Primary query |
|-------|---------|---------------|
| `users_v1` | 1 s | Username, displayName, bio, verified |
| `posts_v1` | 1 s | Content, hashtags, mentions, groupId |
| `hashtags_v1` | 10 s | Tag name, trending score |
| `groups_v1` | 5 s | Name, description, tags, category |

---

## CDC sync (Kafka → ES)

Bulk flush every 5 s. Individually indexed writes are batched to avoid per-document overhead.

| Topic consumed | ES action |
|----------------|-----------|
| `post.created` | Index document |
| `post.updated` | Update document |
| `post.deleted` | Set `isDeleted=true` |
| `post.liked` | Script-update `likeCount` |
| `post.reposted` | Script-update `repostCount` |
| `user.profile.updated` | Update user document |
| `user.verified` | Set `isVerified=true` |
| `group.created` | Index document |
| `group.updated` | Update document |
| `group.deleted` | Set `isDeleted=true` |
| `group.post.created` | Tag post with `groupId` |
| `comment.created` | Index (if comment search enabled) |

---

## Trending hashtag algorithm

```
Every 10 s:
  raw  = Σ(like_count × 2 + repost_count × 3) for posts with this tag in last 24 h
  score = raw / (hours_since_first_use / 24 + 1)   ← time decay

Top-50 cached  Redis key: search:trending:hashtags  TTL 1 min
```

---

## API

```
GET  /api/v1/search?q=&type=ALL|USERS|POSTS|HASHTAGS|GROUPS&page=&size=
GET  /api/v1/search/trending/hashtags?limit=10&period=1h|6h|24h
GET  /api/v1/search/suggestions?q=
GET  /api/v1/search/users?q=&verified=
GET  /api/v1/search/posts?q=&from=&to=&authorId=&groupId=
GET  /api/v1/search/groups?q=&tags=&category=
POST /api/v1/search/admin/reindex         # ADMIN only
GET  /api/v1/search/admin/index-stats
```

---

## Cache keys

| Key | TTL |
|-----|-----|
| `search:trending:hashtags` | 1 min |
| `search:result:{queryHash}` | 30 s |
| `search:suggestions:{prefix}` | 1 min |

---

## Tests

- **Unit:** `SearchQueryBuilderTest`, `TrendingScoreCalculatorTest`, `BulkIndexPipelineTest`
