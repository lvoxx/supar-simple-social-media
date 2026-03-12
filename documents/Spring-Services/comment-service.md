# comment-service

**Type:** Spring Boot · Port `8084`  
**Primary DB:** Apache Cassandra — keyspace `sssm_comments`  
**Cache:** Redis  
**Starters:** `cassandra-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter`

---

## Responsibilities

High-throughput comment và nested-reply management (max depth = 3), **comment interaction** (reactions + view count), và orchestration với `comment-recommendation-service` trước khi trả về paginated comments theo sort BEST.

---

## Why Cassandra

- Append-heavy writes (millions/day), no updates in hot path.
- `TIMEUUID` clustering key → natural cursor pagination không cần `OFFSET`.
- `COUNTER` type — lock-free và atomic cho reaction/reply/view counts.
- Query patterns đơn giản: by `post_id`, by `parent_comment_id` — no JOIN.

---

## DB init

K8S `InitContainer` runs `cqlsh`.  
Scripts: `infrastructure/k8s/db-init/comment-service/cql/`  
`spring.cassandra.schema-action: NONE`

---

## Depth model

```
depth = 0  →  Top-level comment (parent = post)
depth = 1  →  Reply tới depth-0 comment
depth = 2  →  Reply tới depth-1 comment
depth = 3  →  Reply tới depth-2 comment  ← MAX DEPTH

Khi user cố reply vào depth-3 comment → 422 ValidationException
  message: "MAX_REPLY_DEPTH_EXCEEDED"
  Gợi ý UI: "@mention" người dùng kia thay vì tạo thêm nhánh.
```

---

## Schema (keyspace `sssm_comments`)

```cql
-- Top-level và tất cả comment, partition by post
CREATE TABLE comments_by_post (
  post_id           UUID,
  comment_id        TIMEUUID,
  parent_comment_id UUID,
  root_comment_id   UUID,
  author_id         UUID,
  author_username   TEXT,
  author_avatar_url TEXT,
  content           TEXT,
  depth             INT,
  status            TEXT,        -- ACTIVE | HIDDEN | DELETED | SPAM_HIDDEN
  is_deleted        BOOLEAN,
  deleted_at        TIMESTAMP,
  deleted_by        UUID,
  media_ids         LIST<UUID>,
  created_at        TIMESTAMP,
  updated_at        TIMESTAMP,
  PRIMARY KEY (post_id, comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- Replies lookup by parent
CREATE TABLE comments_by_parent (
  parent_comment_id UUID,
  comment_id        TIMEUUID,
  post_id           UUID,
  author_id         UUID,
  author_username   TEXT,
  content           TEXT,
  depth             INT,
  is_deleted        BOOLEAN,
  created_at        TIMESTAMP,
  PRIMARY KEY (parent_comment_id, comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- Top-level comments riêng (phục vụ paging depth-0 hiệu quả)
CREATE TABLE top_comments_by_post (
  post_id    UUID,
  comment_id TIMEUUID,
  author_id  UUID,
  content    TEXT,
  is_deleted BOOLEAN,
  created_at TIMESTAMP,
  PRIMARY KEY (post_id, comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- Reaction dedup
CREATE TABLE comment_reactions (
  comment_id    UUID,
  user_id       UUID,
  post_id       UUID,
  reaction_type TEXT,    -- LIKE | LOVE | HAHA | WOW | SAD | ANGRY
  reacted_at    TIMESTAMP,
  PRIMARY KEY (comment_id, user_id)
);

-- Atomic counters (bảng riêng — Cassandra requirement)
CREATE TABLE comment_counters (
  comment_id   UUID PRIMARY KEY,
  reply_count  COUNTER,
  like_count   COUNTER,
  love_count   COUNTER,
  haha_count   COUNTER,
  wow_count    COUNTER,
  sad_count    COUNTER,
  angry_count  COUNTER
);

-- View counters riêng (Cassandra COUNTER — bảng riêng)
CREATE TABLE comment_view_counters (
  comment_id UUID PRIMARY KEY,
  view_count COUNTER
);
```

---

## View Count — Time-Buffered Strategy

> Cùng pattern với `post-interaction-service` — tránh hit Cassandra mỗi lần user scroll qua comment.

```
Mỗi lần POST /api/v1/comments/{commentId}/view:
  Body: { duration_ms }
  Redis INCR  comment:view-buffer:{commentId}   (TTL 120 s rolling)

CommentViewFlushJob mỗi 60 s:
  1. SCAN Redis keys  comment:view-buffer:*
  2. For each key:
     a. GETDEL key  → delta
     b. UPDATE Cassandra: comment_view_counters COUNTER += delta
     c. Tính display string → nếu bucket thay đổi, cập nhật Redis display cache
  3. Single-batch Cassandra write

Kafka: comment.view.dwell { commentId, postId, userId, duration_ms }
  → consumed bởi comment-recommendation-service để tính dwell-time signals
```

---

## Reaction Display — Bucket Cache

Cùng chiến lược bucket với `post-interaction-service` (`CounterDisplayUtil` từ `common-core`):

| Raw count     | Display |
| ------------- | ------- |
| < 1 000       | exact   |
| 1 000 – 9 999 | `1.2K`  |
| 10 000+       | `12K`   |

```
Cache key: comment:display-count:{commentId}
Value: { like: "1.2K", love: "340", totalReactions: "1.6K", replies: "89", views: "5.6K" }
Chỉ cập nhật Redis khi bucket thay đổi.
```

---

## Paging Strategy

### Sort NEWEST / OLDEST (không qua recommendation)

```
GET /api/v1/posts/{postId}/comments?sort=NEWEST&cursor=&size=20

Cassandra: SELECT FROM top_comments_by_post
WHERE post_id = :postId AND comment_id < :cursor
LIMIT :size;

Cursor = TIMEUUID của comment cuối trang trước.
```

### Sort BEST (qua comment-recommendation-service)

```
GET /api/v1/posts/{postId}/comments?sort=BEST&cursor=&size=20

1. Cassandra: SELECT size × 3 = 60 candidates từ top_comments_by_post
2. gRPC → comment-recommendation-service (RankComments)
   { postId, userId, candidates[60], returnSize: 20 }
3. Trả về 20 comment ids đã reranked
4. Batch fetch full comment data từ comments_by_post WHERE comment_id IN (...)
5. Giữ đúng thứ tự từ recommendation service
```

### Replies của 1 comment (không qua recommendation — replies luôn chronological)

```
GET /api/v1/comments/{commentId}/replies?cursor=&size=10

Cassandra: SELECT FROM comments_by_parent
WHERE parent_comment_id = :commentId AND comment_id < :cursor
LIMIT :size;
```

---

## Comment Interaction Flow

```
POST /api/v1/comments/{commentId}/react
Body: { reactionType: "LIKE" }

1. Dedup check: SELECT FROM comment_reactions WHERE comment_id=? AND user_id=?
   a. Không tồn tại → INSERT + INCR counter tương ứng
   b. Cùng loại → DELETE (toggle off) + DECR counter
   c. Khác loại → UPDATE reaction_type + DECR cũ + INCR mới

2. Đọc raw count → tính display string → cập nhật Redis nếu bucket thay đổi

3. Kafka: comment.reacted { commentId, postId, actorId, authorId, reactionType }
   → notification-service push thông báo
```

---

## Auto-hide từ recommendation service

```
Kafka: comment.spam.detected { commentId, spamScore, autoHide: true }
  → UPDATE comments_by_post SET status = 'SPAM_HIDDEN' WHERE ...
  → UPDATE top_comments_by_post SET is_deleted = TRUE WHERE ...
  (soft-hide, không xoá — admin có thể review và restore)
```

---

## Kafka

### Published

| Topic                | Consumers                                                                   |
| -------------------- | --------------------------------------------------------------------------- |
| `comment.created`    | notification-svc, search-svc, user-analysis-svc, comment-recommendation-svc |
| `comment.reacted`    | notification-svc, user-analysis-svc, comment-recommendation-svc             |
| `comment.view.dwell` | comment-recommendation-svc                                                  |
| `comment.reported`   | ai-dashboard-svc                                                            |
| `comment.deleted`    | search-svc                                                                  |

### Consumed

| Topic                   | Action                                                           |
| ----------------------- | ---------------------------------------------------------------- |
| `post.deleted`          | Soft-delete tất cả comments của post (`status = 'POST_DELETED'`) |
| `user.deleted`          | Soft-delete tất cả comments của user                             |
| `comment.spam.detected` | Auto-hide comment (`status = 'SPAM_HIDDEN'`)                     |

---

## API

```
# Comments
POST   /api/v1/posts/{postId}/comments
       Body: { content, parentCommentId?, mediaIds? }
GET    /api/v1/posts/{postId}/comments?sort=NEWEST|OLDEST|BEST&cursor=&size=
GET    /api/v1/comments/{commentId}/replies?cursor=&size=
PUT    /api/v1/comments/{commentId}          # edit trong 15 phút
DELETE /api/v1/comments/{commentId}

# Reactions
POST   /api/v1/comments/{commentId}/react
       Body: { reactionType: LIKE|LOVE|HAHA|WOW|SAD|ANGRY }
GET    /api/v1/comments/{commentId}/reactions?cursor=&size=  # author-only

# View + dwell (fire-and-forget từ client)
POST   /api/v1/comments/{commentId}/view
       Body: { duration_ms }

# Report
POST   /api/v1/comments/{commentId}/report

# Counts
GET    /api/v1/comments/{commentId}/counts
# Response: { like: "1.2K", love: "340", totalReactions: "1.6K", replies: "89", views: "5.6K" }
```

---

## Cache keys

| Key                                        | TTL                    |
| ------------------------------------------ | ---------------------- |
| `comment:detail:{commentId}`               | 2 min                  |
| `comment:display-count:{commentId}`        | No TTL (bucket-driven) |
| `comment:view-buffer:{commentId}`          | 120 s rolling          |
| `comment:my-reaction:{userId}:{commentId}` | 5 min                  |

---

## Rate limits

| Endpoint         | Limit                           |
| ---------------- | ------------------------------- |
| `POST /comments` | 100/hour per userId             |
| `POST /react`    | 500/hour per userId             |
| `POST /view`     | 20/min per userId per commentId |

---

## Tests

- **Unit:** `CommentServiceTest`, `CommentReactionServiceTest`, `CommentViewFlushJobTest`, `DepthValidatorTest`
- **Integration:** Cassandra + Kafka + gRPC stub (comment-recommendation-service WireMock) (Testcontainers)
- **Automation:** create → nested reply (depth 0→3) → reject depth 4 → react → toggle → view dwell → flush → BEST sort → spam auto-hide → paginate
