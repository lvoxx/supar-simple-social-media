# post-service

**Type:** Spring Boot · Port `8083`  
**Primary DB:** PostgreSQL (R2DBC) — schema `sssm_posts`  
**Cache:** Redis  
**Starters:** `postgres-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter`

---

## Responsibilities

Core posting engine. Post lifecycle (CRUD, status transitions), home/explore feeds, group-context posts, và system auto-posts. **Không sở hữu interaction logic** — like, share, bookmark, view counter thuộc về `post-interaction-service`. Feed scoring đọc counter thông qua gRPC từ `post-interaction-service`.

---

## DB init

K8S `Job` runs Flyway CLI.  
Scripts: `infrastructure/k8s/db-init/post-service/sql/`

---

## Schema (schema `sssm_posts`)

```sql
-- posts
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
author_id        UUID        NOT NULL
group_id         UUID                          -- NULL khi không trong group
content          TEXT
repost_of_id     UUID                          -- NULL với ORIGINAL; app-enforced ref
quoted_post_id   UUID                          -- NULL với ORIGINAL; app-enforced ref
post_type        VARCHAR(20) NOT NULL          -- ORIGINAL|REPOST|QUOTE|AUTO
status           VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
                                               -- DRAFT|PENDING_MEDIA|PUBLISHED|PENDING_REVIEW|HIDDEN|DELETED
visibility       VARCHAR(20) NOT NULL DEFAULT 'PUBLIC'
is_edited        BOOLEAN     NOT NULL DEFAULT FALSE
edited_at        TIMESTAMPTZ
is_pinned        BOOLEAN     NOT NULL DEFAULT FALSE
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at       TIMESTAMPTZ
created_by       UUID
updated_by       UUID
is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE
deleted_at       TIMESTAMPTZ
deleted_by       UUID

-- post_media  (no FK — app-enforced)
post_id          UUID        NOT NULL
media_id         UUID        NOT NULL
position         INT         NOT NULL DEFAULT 0
PRIMARY KEY (post_id, media_id)

-- post_hashtags
post_id          UUID        NOT NULL
hashtag          VARCHAR(100) NOT NULL
PRIMARY KEY (post_id, hashtag)

-- post_mentions
post_id          UUID        NOT NULL
mentioned_user_id UUID       NOT NULL
PRIMARY KEY (post_id, mentioned_user_id)

-- post_edits  (audit, append-only)
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
post_id          UUID        NOT NULL
previous_content TEXT
edited_at        TIMESTAMPTZ NOT NULL
edited_by        UUID        NOT NULL

-- post_reports
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
post_id          UUID        NOT NULL
reporter_id      UUID        NOT NULL
reason           VARCHAR(50)
status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

> **Đã xoá khỏi schema:** `reply_to_id`, `like_count`, `repost_count`, `reply_count`, `bookmark_count`, `view_count`, `post_likes`, `post_bookmarks`.  
> Các counter này thuộc về `post-interaction-service` (Cassandra) và `comment-service`.

---

## Repost / Quote Post

Khi user repost hoặc quote-post:

```
1. post-service tạo bản ghi mới:
   INSERT INTO posts (post_type='REPOST'|'QUOTE', repost_of_id=:originalId, ...)
   → Trả về post_id mới (shared post có id riêng)

2. post-service publish Kafka: post.created { postId, postType: 'REPOST', repostOfId }

3. post-service gọi gRPC tới post-interaction-service:
   PostInteractionService.RegisterShare(postId=originalId, actorId=userId)
   → post-interaction-service tăng share_count của post gốc + ghi actor record

4. notification-service nhận post.created → push thông báo cho author của post gốc
```

Interaction trên shared post (post mới) được track độc lập qua post-interaction-service với `post_id` của shared post.

---

## Feed algorithms

**Home feed** — posts từ followed users, sort `created_at DESC`, cursor pagination.  
Cache: Redis Sorted Set `feed:home:{userId}` TTL 30 s.

**Explore feed** — scored với counter từ post-interaction-service (gRPC BatchGetPostCounts):

```
score = (like_raw × 2 + share_raw × 3 + comment_count_approx)
        / (EXTRACT(EPOCH FROM NOW() - created_at) / 3600 + 2)
```

Cache: `feed:explore:global` TTL 1 min.

---

## Kafka

### Published

| Topic           | Consumers                                                                          |
| --------------- | ---------------------------------------------------------------------------------- |
| `post.created`  | notification-svc, search-svc, user-analysis-svc, post-interaction-svc (nếu REPOST) |
| `post.updated`  | search-svc                                                                         |
| `post.deleted`  | search-svc, comment-svc, post-interaction-svc, bookmark-svc                        |
| `post.reported` | ai-dashboard-svc                                                                   |

> **Đã xoá:** `post.liked`, `post.reposted`, `post.bookmarked` — các event này nay phát từ `post-interaction-service` và `bookmark-service`.

### Consumed

| Topic                     | Action                                         |
| ------------------------- | ---------------------------------------------- |
| `media.upload.completed`  | Promote `PENDING_MEDIA` post → `PUBLISHED`     |
| `media.upload.failed`     | Mark post `MEDIA_FAILED`                       |
| `user.avatar.changed`     | Create `AUTO` post (nếu user setting cho phép) |
| `user.background.changed` | Create `AUTO` post (nếu user setting cho phép) |

---

## API

```
POST   /api/v1/posts
GET    /api/v1/posts/{postId}
PUT    /api/v1/posts/{postId}
DELETE /api/v1/posts/{postId}
GET    /api/v1/posts/{postId}/thread        # original + quoted/reposted chain

# Repost / Quote (tạo shared post với id riêng)
POST   /api/v1/posts/{postId}/repost
DELETE /api/v1/posts/{postId}/repost        # xoá bản repost của mình

POST   /api/v1/posts/{postId}/report

GET    /api/v1/posts/feed/home
GET    /api/v1/posts/feed/explore
GET    /api/v1/users/{userId}/posts
```

> **Đã xoá khỏi API:** `/like`, `/bookmark`, `/view`, `/users/{userId}/likes`, `/users/{userId}/bookmarks`  
> → Các endpoint này thuộc về `post-interaction-service` và `bookmark-service`.

---

## gRPC (server)

```protobuf
service PostService {
  rpc FindPostById     (FindPostByIdRequest)    returns (PostResponse);
  rpc CheckPostExists  (CheckPostExistsRequest) returns (CheckPostExistsResponse);
  rpc FindPostsByIds   (FindPostsByIdsRequest)  returns (PostListResponse);  // batch cho feed
}

message FindPostsByIdsRequest { repeated string post_ids = 1; }

message PostResponse {
  string post_id        = 1;
  string author_id      = 2;
  string content        = 3;
  string status         = 4;
  string post_type      = 5;
  string repost_of_id   = 6;
  string quoted_post_id = 7;
  int64  created_at_ms  = 8;
}
message PostListResponse { repeated PostResponse posts = 1; }
```

---

## Cache keys

| Key                    | TTL   |
| ---------------------- | ----- |
| `post:detail:{postId}` | 5 min |
| `feed:home:{userId}`   | 30 s  |
| `feed:explore:global`  | 1 min |

---

## Rate limits

| Endpoint                | Limit              |
| ----------------------- | ------------------ |
| `POST /posts`           | 30/hour per userId |
| `POST /{postId}/report` | 10/day per userId  |

---

## Tests

- **Unit:** `PostServiceImplTest`, `FeedServiceTest`, `RepostServiceTest`
- **Integration:** PostgreSQL + Kafka + gRPC stub (post-interaction-service WireMock)
- **Automation:** create → repost (shared post id riêng) → feed verify → delete → cascade check
