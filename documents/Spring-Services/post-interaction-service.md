# post-interaction-service

**Type:** Spring Boot · Port `8087`  
**Primary DB:** Apache Cassandra — keyspace `sssm_post_interactions`  
**Cache:** Redis  
**Starters:** `cassandra-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter`

---

## Responsibilities

Owns all post-level interaction signals: **like**, **share** (repost), and **bookmark counting** (không sở hữu CRUD bookmark — đó là bookmark-service). Duy trì bộ đếm tương tác có bucket display, lưu danh sách user đã tương tác để author tra cứu (ẩn như X, không công khai như Facebook), quản lý view count với time-buffered flush, và phát Kafka event để notification-service push thông báo cho like/share (bookmark không push).

Shared-post (repost/quote) có `post_id` riêng và chứa `original_post_id`; interaction trên shared-post được track độc lập, không gộp vào post gốc.

---

## Why Cassandra

- Write-heavy: hàng triệu like/view mỗi ngày — append-only phù hợp Cassandra.
- `COUNTER` columns — atomic increment không cần distributed lock.
- Partition by `(post_id, interaction_type)` → O(1) lookup cho actor list.
- No JOIN, no transaction needed.

---

## DB init

K8S `InitContainer` runs `cqlsh`.  
Scripts: `infrastructure/k8s/db-init/post-interaction-service/cql/`  
`spring.cassandra.schema-action: NONE`

---

## Schema (keyspace `sssm_post_interactions`)

```cql
-- Ghi lại ai đã tương tác với post (author-only visibility)
-- Partition: (post_id, interaction_type) → scale tốt cho viral post
CREATE TABLE post_interaction_actors (
  post_id          UUID,
  interaction_type TEXT,        -- LIKE | SHARE | BOOKMARK
  user_id          TIMEUUID,    -- TIMEUUID → tự nhiên sort theo thời gian
  created_at       TIMESTAMP,
  PRIMARY KEY ((post_id, interaction_type), user_id)
) WITH CLUSTERING ORDER BY (user_id DESC)
  AND default_time_to_live = 15552000;   -- 180 ngày TTL

-- Dedup check: user đã like/share post này chưa?
CREATE TABLE post_interaction_by_user (
  user_id          UUID,
  interaction_type TEXT,
  post_id          TIMEUUID,
  created_at       TIMESTAMP,
  PRIMARY KEY ((user_id, interaction_type), post_id)
) WITH CLUSTERING ORDER BY (post_id DESC)
  AND default_time_to_live = 15552000;

-- Atomic counters (Cassandra yêu cầu bảng riêng cho COUNTER)
CREATE TABLE post_interaction_counters (
  post_id        UUID PRIMARY KEY,
  like_count     COUNTER,
  share_count    COUNTER,
  bookmark_count COUNTER
);

-- View count riêng để tránh flush liên tục (xem chiến lược bên dưới)
CREATE TABLE post_view_counters (
  post_id    UUID PRIMARY KEY,
  view_count COUNTER
);
```

---

## Bộ đếm Display — Bucket Cache Strategy

> **Mục tiêu:** Tránh cache invalidation liên tục khi counter tăng từng đơn vị.

### Quy tắc bucket

| Raw count         | Display         | Bucket unit |
| ----------------- | --------------- | ----------- |
| 0 – 999           | `"999"` (exact) | 1           |
| 1 000 – 9 999     | `"1.2K"`        | 100         |
| 10 000 – 99 999   | `"12K"`         | 1 000       |
| 100 000 – 999 999 | `"123K"`        | 10 000      |
| 1 000 000+        | `"1.2M"`        | 100 000     |

Ví dụ: 1 234 → `"1.2K"`, 1 235 → `"1.2K"`, 1 300 → `"1.3K"`.

### Cơ chế

```
1. User nhấn like → ghi vào Cassandra COUNTER (async, fire-and-forget).
2. Sau mỗi COUNTER INCREMENT, đọc lại raw count.
3. Tính display string mới theo quy tắc bucket.
4. So sánh với Redis cache: post:display-count:{postId}.
   - Nếu display string KHÁC → SET mới (cache miss chủ động).
   - Nếu GIỐNG → bỏ qua, không ghi Redis.
5. Client luôn đọc từ Redis.
```

```java
// CounterDisplayUtil.java
public static String format(long raw) {
    if (raw < 1_000)    return String.valueOf(raw);
    if (raw < 10_000)   return String.format("%.1fK", Math.floor(raw / 100.0) / 10);
    if (raw < 100_000)  return (raw / 1_000) + "K";
    if (raw < 1_000_000) return (raw / 10_000 * 10) + "K";
    return String.format("%.1fM", Math.floor(raw / 100_000.0) / 10);
}
```

Redis key: `post:display-count:{postId}` → JSON `{like:"1.2K", share:"340", bookmark:"89", view:"5.6K"}`  
TTL: **không set TTL** — chỉ expire khi bucket thay đổi.

---

## View Count — Time-Buffered Strategy

> **Mục tiêu:** Không hit Cassandra mỗi lần user load post.

```
Mỗi lần POST /posts/{postId}/view:
  Redis INCR  post:view-buffer:{postId}   (TTL 120 s rolling)

Scheduled job mỗi 60 s (ViewFlushJob):
  1. SCAN Redis keys  post:view-buffer:*
  2. For each key:
     a. GETDEL key  → delta
     b. UPDATE Cassandra: post_view_counters COUNTER += delta
     c. Tính display string → nếu bucket thay đổi, cập nhật Redis display cache
  3. Single-batch Cassandra write để tránh thundering herd.
```

Nếu Redis key expire trước khi flush (TTL 120 s, job chạy 60 s) → delta mất, chấp nhận được vì view count không cần chính xác tuyệt đối.

---

## Interaction Flow (Like / Share)

```
Client → POST /api/v1/posts/{postId}/like

1. Kiểm tra dedup: SELECT FROM post_interaction_by_user WHERE user_id=? AND interaction_type='LIKE' AND post_id=?
   → Đã tồn tại → 409 ConflictException

2. Ghi actor record:
   INSERT INTO post_interaction_actors (post_id, 'LIKE', user_id_timeuuid, now())

3. Ghi dedup record:
   INSERT INTO post_interaction_by_user (user_id, 'LIKE', post_id_timeuuid, now())

4. Increment counter:
   UPDATE post_interaction_counters SET like_count = like_count + 1 WHERE post_id = ?

5. Đọc raw like_count → tính display → cập nhật Redis nếu bucket thay đổi

6. Publish Kafka: post.interaction.created
   { postId, actorId, authorId, type: "LIKE", timestamp }
   → notification-service push thông báo cho author
```

> **Bookmark:** Luồng tương tự nhưng KHÔNG publish Kafka event → không có notification.

---

## Unlike / Unshare / Unbookmark

```
1. Kiểm tra tồn tại trong post_interaction_by_user → không có → 404
2. DELETE FROM post_interaction_actors WHERE post_id=? AND interaction_type=? AND user_id=?
3. DELETE FROM post_interaction_by_user WHERE user_id=? AND interaction_type=? AND post_id=?
4. UPDATE post_interaction_counters SET like_count = like_count - 1 WHERE post_id = ?
   (Guard: đọc trước khi trừ, nếu = 0 thì skip)
5. Cập nhật Redis display cache nếu bucket thay đổi
```

---

## Kafka

### Published

| Topic                      | Payload                                                     | Consumer                            |
| -------------------------- | ----------------------------------------------------------- | ----------------------------------- |
| `post.interaction.created` | `{postId, actorId, authorId, type: LIKE\|SHARE, timestamp}` | notification-svc, user-analysis-svc |
| `post.interaction.deleted` | `{postId, actorId, type, timestamp}`                        | user-analysis-svc                   |

> Bookmark không phát event ở đây — bookmark-service tự phát `post.bookmarked`.

### Consumed

| Topic               | Action                                              |
| ------------------- | --------------------------------------------------- |
| `post.bookmarked`   | Increment `bookmark_count` + ghi actor record       |
| `post.unbookmarked` | Decrement `bookmark_count` + xoá actor record       |
| `post.deleted`      | Soft-clean counters (đặt về 0, giữ history 30 ngày) |

---

## API

```
# Like
POST   /api/v1/posts/{postId}/like
DELETE /api/v1/posts/{postId}/like

# Share (repost / quote — post-service tạo post mới, sau đó gọi đây)
POST   /api/v1/posts/{postId}/share
DELETE /api/v1/posts/{postId}/share

# Bookmark count signal (CRUD bookmark ở bookmark-service)
POST   /api/v1/posts/{postId}/bookmark-signal
DELETE /api/v1/posts/{postId}/bookmark-signal

# View
POST   /api/v1/posts/{postId}/view

# Display counts (Redis-first)
GET    /api/v1/posts/{postId}/counts
# Response: { like: "1.2K", share: "340", bookmark: "89", view: "5.6K" }

# Author-only: danh sách user đã like (ẩn với public)
GET    /api/v1/posts/{postId}/likers?cursor=&size=20
GET    /api/v1/posts/{postId}/sharers?cursor=&size=20

# User state: người dùng hiện tại đã like/share/bookmark post này chưa?
GET    /api/v1/posts/{postId}/my-interactions
# Response: { liked: true, shared: false, bookmarked: true }
```

---

## gRPC (consumed by post-service, bookmark-service)

```protobuf
service PostInteractionService {
  rpc GetPostCounts      (GetPostCountsRequest)      returns (PostCountsResponse);
  rpc GetUserInteraction (GetUserInteractionRequest)  returns (UserInteractionResponse);
  rpc BatchGetPostCounts (BatchGetPostCountsRequest)  returns (BatchPostCountsResponse);
}

message GetPostCountsRequest    { string post_id = 1; }
message GetUserInteractionRequest {
  string post_id = 1;
  string user_id = 2;
}
message BatchGetPostCountsRequest { repeated string post_ids = 1; }

message PostCountsResponse {
  string like_display     = 1;   // "1.2K"
  string share_display    = 2;
  string bookmark_display = 3;
  string view_display     = 4;
  int64  like_raw         = 5;   // for feed scoring
  int64  share_raw        = 6;
}
message UserInteractionResponse {
  bool liked      = 1;
  bool shared     = 2;
  bool bookmarked = 3;
}
message BatchPostCountsResponse {
  map<string, PostCountsResponse> counts = 1;  // postId → counts
}
```

---

## Cache keys

| Key                                        | Value                                        | TTL                    |
| ------------------------------------------ | -------------------------------------------- | ---------------------- |
| `post:display-count:{postId}`              | `{like,share,bookmark,view}` display strings | No TTL (bucket-driven) |
| `post:view-buffer:{postId}`                | Raw view delta (Redis INCR)                  | 120 s rolling          |
| `post:interaction-state:{userId}:{postId}` | `{liked,shared,bookmarked}` boolean JSON     | 5 min                  |

---

## Rate limits

| Endpoint     | Limit                                       |
| ------------ | ------------------------------------------- |
| `POST /like` | 300/hour per userId                         |
| `POST /view` | 10/min per userId per postId (dedup window) |

---

## Tests

- **Unit:** `InteractionServiceTest`, `CounterDisplayUtilTest`, `ViewFlushJobTest`, `BucketCacheStrategyTest`
- **Integration:** Cassandra + Redis + Kafka (Testcontainers)
- **Automation:** like → unlike → share → view burst → flush job → display count verify → author liker list
