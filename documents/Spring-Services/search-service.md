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
- **Integration:** Elasticsearch + Kafka containers
- **Automation:** index → search → update likeCount → re-search → verify score change
