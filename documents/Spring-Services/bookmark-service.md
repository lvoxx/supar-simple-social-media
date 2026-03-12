# bookmark-service

**Type:** Spring Boot · Port `8088`  
**Primary DB:** PostgreSQL (R2DBC) — schema `sssm_bookmarks`  
**Cache:** Redis  
**Starters:** `postgres-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter`

---

## Responsibilities

CRUD hoàn chỉnh cho bookmark của user: lưu post vào bộ sưu tập, phân loại theo category, tìm kiếm, sort, range filter, và xoá. **Không sở hữu bộ đếm bookmark** — khi user save/unsave một post, service này phát Kafka event để post-interaction-service cập nhật counter. Không dùng relationship (foreign key) — tất cả constraint được enforce ở tầng application.

---

## DB init

K8S `Job` runs Flyway CLI.  
Scripts: `infrastructure/k8s/db-init/bookmark-service/sql/`

---

## Schema (schema `sssm_bookmarks`)

```sql
-- Bộ sưu tập bookmark (do user tự tạo)
-- id giả UUID, không FK
id            UUID        PRIMARY KEY DEFAULT gen_random_uuid()
user_id       UUID        NOT NULL
name          VARCHAR(100) NOT NULL
description   TEXT
color         VARCHAR(7)              -- hex color, e.g. "#FF5733"
icon          VARCHAR(50)             -- emoji hoặc icon key
is_default    BOOLEAN     NOT NULL DEFAULT FALSE   -- "Saved" mặc định
visibility    VARCHAR(20) NOT NULL DEFAULT 'PRIVATE'
                                      -- PRIVATE | PUBLIC_LINK (share link)
post_count    INT         NOT NULL DEFAULT 0
created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at    TIMESTAMPTZ
is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE
deleted_at    TIMESTAMPTZ

-- Index
CREATE INDEX idx_bookmark_collections_user ON bookmark_collections (user_id)
  WHERE is_deleted = FALSE;

-- Bookmark entry (post trong collection)
post_id           UUID        NOT NULL
user_id           UUID        NOT NULL
collection_id     UUID        NOT NULL      -- app-enforced ref tới bookmark_collections
note              TEXT                      -- user note / annotation
saved_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
post_author_id    UUID        NOT NULL      -- denormalised, tránh join
post_content_preview TEXT                  -- denormalised snippet ≤ 280 chars
post_media_thumb  TEXT                      -- denormalised CDN URL của thumbnail đầu tiên
post_created_at   TIMESTAMPTZ NOT NULL      -- denormalised, phục vụ sort
PRIMARY KEY (post_id, user_id)              -- 1 user bookmark 1 post 1 lần

-- Composite indexes cho search/range/sort
CREATE INDEX idx_bookmarks_user_collection ON bookmarks (user_id, collection_id)
  WHERE is_deleted = FALSE;               -- (thêm cột is_deleted nếu cần soft-delete)
CREATE INDEX idx_bookmarks_user_saved_at  ON bookmarks (user_id, saved_at DESC);
CREATE INDEX idx_bookmarks_collection_post_created
  ON bookmarks (collection_id, post_created_at DESC);

-- Full-text search: GIN index trên preview content
CREATE INDEX idx_bookmarks_fts ON bookmarks
  USING GIN (to_tsvector('simple', COALESCE(post_content_preview, '')));
```

> **Lưu ý:** Không dùng FK (`collection_id` không ref bảng kia). Application kiểm tra collection tồn tại và thuộc user trước khi INSERT.

---

## Denormalisation strategy

Khi lưu bookmark, service gọi gRPC tới `post-service` để lấy:

- `author_id`
- `content` (cắt ≤ 280 chars làm preview)
- `created_at`
- media thumbnail đầu tiên (từ post-service trả về)

Dữ liệu này được lưu vào `bookmarks` để tránh JOIN cross-service khi query. Khi post bị xoá, `post.deleted` Kafka event cập nhật lại `post_content_preview = '[Post đã bị xoá]'`.

---

## Collection lifecycle

```
Khi user đăng ký (hoặc lần đầu bookmark):
  Auto-create collection "Đã lưu" với is_default = TRUE

Khi xoá collection:
  - Nếu is_default = TRUE → reject (403)
  - Nếu còn bookmarks → reject (409) hoặc move to default rồi xoá
  - Decrement post_count = 0 → soft-delete collection

Khi bookmark một post vào collection:
  INSERT INTO bookmarks ...
  UPDATE bookmark_collections SET post_count = post_count + 1
  Publish Kafka: post.bookmarked { postId, userId }

Khi xoá bookmark:
  DELETE FROM bookmarks WHERE post_id=? AND user_id=?
  UPDATE bookmark_collections SET post_count = post_count - 1
  Publish Kafka: post.unbookmarked { postId, userId }
```

---

## Query capabilities

### Range filter

```sql
-- Bookmark trong khoảng thời gian
SELECT * FROM bookmarks
WHERE user_id = :userId AND collection_id = :collectionId
  AND saved_at BETWEEN :from AND :to
ORDER BY saved_at DESC
LIMIT :size OFFSET :offset;

-- Bookmark theo ngày post được tạo
SELECT * FROM bookmarks
WHERE user_id = :userId
  AND post_created_at BETWEEN :from AND :to
ORDER BY post_created_at DESC;
```

### Sort options

| Sort key         | Column            | Order              |
| ---------------- | ----------------- | ------------------ |
| `SAVED_AT_DESC`  | `saved_at`        | Mới nhất (default) |
| `SAVED_AT_ASC`   | `saved_at`        | Cũ nhất            |
| `POST_DATE_DESC` | `post_created_at` | Post mới nhất      |
| `POST_DATE_ASC`  | `post_created_at` | Post cũ nhất       |

### Full-text search

```sql
SELECT *, ts_rank(to_tsvector('simple', post_content_preview),
                   plainto_tsquery('simple', :query)) AS rank
FROM bookmarks
WHERE user_id = :userId
  AND to_tsvector('simple', COALESCE(post_content_preview, ''))
        @@ plainto_tsquery('simple', :query)
ORDER BY rank DESC
LIMIT :size OFFSET :offset;
```

### Bulk delete

```sql
-- Xoá nhiều bookmark trong 1 lần
DELETE FROM bookmarks
WHERE user_id = :userId AND post_id = ANY(:postIds);
```

---

## Kafka

### Published

| Topic               | Payload                                     | Consumer                            |
| ------------------- | ------------------------------------------- | ----------------------------------- |
| `post.bookmarked`   | `{postId, userId, collectionId, timestamp}` | post-interaction-svc (tăng counter) |
| `post.unbookmarked` | `{postId, userId, timestamp}`               | post-interaction-svc (giảm counter) |

### Consumed

| Topic          | Action                                                                    |
| -------------- | ------------------------------------------------------------------------- |
| `post.deleted` | Cập nhật `post_content_preview = '[Post đã bị xoá]'`, giữ bookmark record |
| `user.deleted` | Hard-delete tất cả bookmarks và collections của user                      |

---

## API

```
# Collections
GET    /api/v1/bookmarks/collections                    # list my collections
POST   /api/v1/bookmarks/collections                    # tạo collection mới
PUT    /api/v1/bookmarks/collections/{collectionId}     # đổi tên/màu/icon
DELETE /api/v1/bookmarks/collections/{collectionId}     # xoá collection

# Bookmark CRUD
POST   /api/v1/bookmarks                                # save post
       Body: { postId, collectionId?, note? }
DELETE /api/v1/bookmarks/{postId}                       # unsave post
PATCH  /api/v1/bookmarks/{postId}                       # chuyển collection / sửa note
       Body: { collectionId?, note? }

# Listing với range/sort/search
GET    /api/v1/bookmarks?collectionId=&sort=SAVED_AT_DESC&page=&size=
GET    /api/v1/bookmarks?from=&to=&sort=POST_DATE_DESC&page=&size=
GET    /api/v1/bookmarks/search?q=&collectionId=&page=&size=

# Bulk delete
DELETE /api/v1/bookmarks/bulk
       Body: { postIds: ["uuid1", "uuid2"] }

# Check: post này đã được bookmark chưa? (phục vụ UI)
GET    /api/v1/bookmarks/check/{postId}
# Response: { bookmarked: true, collectionId: "uuid" }
```

---

## gRPC (consumed by post-service khi build feed)

```protobuf
service BookmarkService {
  rpc CheckBookmarked (CheckBookmarkedRequest) returns (CheckBookmarkedResponse);
  rpc BatchCheckBookmarked (BatchCheckBookmarkedRequest) returns (BatchCheckBookmarkedResponse);
}

message CheckBookmarkedRequest {
  string post_id = 1;
  string user_id = 2;
}
message CheckBookmarkedResponse {
  bool   bookmarked     = 1;
  string collection_id  = 2;
}
message BatchCheckBookmarkedRequest {
  repeated string post_ids = 1;
  string          user_id  = 2;
}
message BatchCheckBookmarkedResponse {
  map<string, bool> bookmarked_map = 1;   // postId → bool
}
```

---

## Cache keys

| Key                                | Value                        | TTL   |
| ---------------------------------- | ---------------------------- | ----- |
| `bookmark:collections:{userId}`    | JSON list of collections     | 5 min |
| `bookmark:check:{userId}:{postId}` | `{bookmarked, collectionId}` | 5 min |

Khi user thay đổi bookmark → invalidate `bookmark:collections:{userId}` và `bookmark:check:{userId}:{postId}`.

---

## Rate limits

| Endpoint                      | Limit                                  |
| ----------------------------- | -------------------------------------- |
| `POST /bookmarks`             | 500/day per userId                     |
| `POST /bookmarks/collections` | 20/day per userId (max 50 collections) |

---

## Tests

- **Unit:** `BookmarkServiceTest`, `CollectionServiceTest`, `BookmarkSearchServiceTest`
- **Integration:** PostgreSQL + Kafka (Testcontainers)
- **Automation:** create collection → bookmark → range query → search → move to collection → bulk delete
