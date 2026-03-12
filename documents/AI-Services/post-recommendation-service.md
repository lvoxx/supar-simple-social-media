# post-recommendation-service

**Type:** FastAPI · Port `8094`  
**Language:** Python 3.12

---

## AI-native storage architecture

| Layer | Technology | Role |
|-------|-----------|------|
| Operational store | **PostgreSQL** (asyncpg) | Scored post snapshots, signal audit log, model versions, A/B experiment config |
| Feature store | **Redis Stack** (RedisJSON + RedisTimeSeries) | Per-user interest vectors, per-post signal cache, dwell-time buffers, hide/block windows |
| Vector store | **Qdrant** | User interest embeddings + post content embeddings cho ANN interest-matching |
| Experiment tracking | **MLflow** (shared instance) | Training runs, feature importance sweeps, ranking model versions |
| Model registry | **MLflow Model Registry** | Stage-gated promotion (`Development → Staging → Production → Archived`) |
| Artifact store | **MinIO** | Ranking model checkpoints, feature definition files |

---

## Vị trí trong luồng dữ liệu

```
Client → GET /api/v1/posts/feed/home?cursor=&size=20
         ↓
    [post-service]
    1. Query PostgreSQL → lấy candidate posts (size × 3 = 60 candidates)
    2. gRPC → post-interaction-service (BatchGetPostCounts cho 60 posts)
    3. gRPC → post-recommendation-service (RankPosts)
       Input:  { userId, candidates: [{postId, authorId, createdAt, counts}] }
       Output: { rankedPostIds: [top 20 ids theo score] }
    4. Lấy đúng 20 posts theo thứ tự trả về
    5. Return to client
```

> **Không blocking hard:** Nếu recommendation service timeout (> 400 ms) hoặc lỗi, post-service fallback về `created_at DESC` (chronological order). Người dùng không thấy lỗi.

---

## Tại sao pipeline 2 bước (post-guard → recommendation)

```
[Khi post được tạo — đã có sẵn]:
  post-service → post-guard-service → quyết định APPROVED/FLAGGED/REJECTED
  post-guard lưu: { decision, categories, target_audience, confidence }
  Kafka: post.guard.completed → recommendation-service consume → lưu vào signal store

[Khi feed được request]:
  recommendation-service ĐỌC kết quả đã có từ post-guard (không gọi lại)
  + kết hợp real-time signals (dwell, hide, interaction counts)
  → ranking score
```

post-guard làm content safety + audience classification **một lần** lúc tạo post.  
recommendation-service làm **personalized ranking** lúc serve feed.

---

## DB init

K8S `InitContainer` + `Job` chạy scripts.  
Scripts: `infrastructure/k8s/db-init/post-recommendation-service/`

---

## PostgreSQL schema (schema `sssm_recommendation`)

```sql
-- post_signals: snapshot tín hiệu mỗi post để training và audit
id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid()
post_id            UUID        NOT NULL UNIQUE
author_id          UUID        NOT NULL
guard_decision     VARCHAR(20)             -- APPROVED | FLAGGED (from post-guard)
guard_categories   TEXT[]                  -- denormalised
target_audience    JSONB                   -- { locations[], fields[], age_range, languages[] }
like_count         BIGINT      NOT NULL DEFAULT 0
share_count        BIGINT      NOT NULL DEFAULT 0
comment_count      INT         NOT NULL DEFAULT 0
view_count         BIGINT      NOT NULL DEFAULT 0
bookmark_count     INT         NOT NULL DEFAULT 0
avg_dwell_ms       INT         NOT NULL DEFAULT 0   -- trung bình thời gian dừng xem
p90_dwell_ms       INT         NOT NULL DEFAULT 0   -- p90 dwell (filter outlier)
hide_count         INT         NOT NULL DEFAULT 0
block_count        INT         NOT NULL DEFAULT 0
report_count       INT         NOT NULL DEFAULT 0
like_view_ratio    FLOAT       NOT NULL DEFAULT 0   -- like_count / view_count
scroll_stop_rate   FLOAT       NOT NULL DEFAULT 0   -- sessions dừng lại / tổng impressions
bot_author_score   FLOAT       NOT NULL DEFAULT 0   -- từ user-analysis-service
recommendation_score FLOAT     NOT NULL DEFAULT 0   -- score cuối cùng
last_scored_at     TIMESTAMPTZ
created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at         TIMESTAMPTZ

-- hide_block_events: tín hiệu ẩn/chặn với undo window
id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid()
post_id            UUID        NOT NULL
user_id            UUID        NOT NULL
event_type         VARCHAR(10) NOT NULL    -- HIDE | BLOCK
is_undone          BOOLEAN     NOT NULL DEFAULT FALSE   -- TRUE = bỏ qua điểm này
undone_at          TIMESTAMPTZ
created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()

-- ab_experiments: A/B test config cho ranking algorithms
id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid()
name               VARCHAR(100) NOT NULL
algorithm          VARCHAR(50) NOT NULL    -- WEIGHTED_LINEAR | LTR_GBDT | NEURAL_MF
traffic_pct        INT         NOT NULL    -- phần trăm user group
is_active          BOOLEAN     NOT NULL DEFAULT TRUE
config             JSONB
created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

---

## Qdrant collections

```python
# post_content: post embeddings cho content-based filtering
client.create_collection(
    collection_name="post_content",
    vectors_config=VectorParams(size=384, distance=Distance.COSINE),
    hnsw_config=HnswConfigDiff(m=16, ef_construct=200)
)
# Payload per point:
# { post_id, author_id, fields[], locations[], language,
#   created_at_ts, guard_decision, recommendation_score }

# user_interests: user interest embeddings (cập nhật định kỳ từ interaction history)
client.create_collection(
    collection_name="user_interests",
    vectors_config=VectorParams(size=384, distance=Distance.COSINE),
    hnsw_config=HnswConfigDiff(m=16, ef_construct=100)
)
# Payload per point:
# { user_id, top_fields[], top_locations[], updated_at }
```

---

## Redis feature store

```python
# Dwell-time buffer (từ post-interaction-service gửi qua Kafka)
# Key: ts:post:dwell:{postId}  (RedisTimeSeries, 1h retention)
# Flush job mỗi 5 phút → tính avg_dwell_ms và scroll_stop_rate → update PostgreSQL

# Per-user interest vector (RedisJSON, TTL 30 min)
# Key: json:user:interests:{userId}
# Value: { fields: [{name, weight}], locations: [{name, weight}], languages: [] }
# Cập nhật sau mỗi lần user tương tác (Kafka consumer)

# Hide/block window cache (Redis SET, TTL 24h)
# Key: post:hidden:{userId}  → SET of postIds
# Key: post:blocked-author:{userId} → SET of authorIds
# Khi undo trong 24h → SREM, set is_undone=true trong PostgreSQL

# Per-post signal cache (Redis Hash, TTL 2 min — không cần precise)
# Key: post:rec-signals:{postId}
# Fields: like_view_ratio, scroll_stop_rate, bot_author_score, avg_dwell_ms
```

---

## Scoring model — Weighted Linear (default)

Mỗi candidate post được tính `recommendation_score ∈ [0, 1]`:

```python
def score_post(post: CandidatePost, user_ctx: UserContext) -> float:
    """
    Tất cả weights có thể tune qua MLflow experiment tracking.
    """
    # ── 1. QUALITY GATE (hard filter trước) ───────────────────────────────────
    if post.guard_decision == "REJECTED":
        return 0.0
    if post.bot_author_score > 0.85:          # tác giả nhiều khả năng là bot
        return 0.0
    if post.hide_count > threshold_hide:       # quá nhiều người ẩn
        return 0.0

    # ── 2. Engagement quality signals ─────────────────────────────────────────
    # like/view ratio: natural range 0.02–0.10; ngoài range = suspicious
    like_view_ratio  = post.like_count / max(post.view_count, 1)
    ratio_score      = gaussian_score(like_view_ratio, mean=0.05, sigma=0.03)
    # sigmoid — quá cao (bot-inflated) bị phạt; quá thấp cũng bị phạt

    dwell_score      = min(post.avg_dwell_ms / TARGET_DWELL_MS, 1.0)  # TARGET = 8000ms
    scroll_stop      = post.scroll_stop_rate     # 0–1, TikTok-like signal
    engagement_score = (
        ratio_score       * 0.20 +
        dwell_score       * 0.25 +   # dwell time là signal mạnh nhất
        scroll_stop       * 0.20
    )

    # ── 3. Interaction volume (log-normalised) ─────────────────────────────────
    import math
    volume_score = (
        math.log1p(post.like_count)    * 0.04 +
        math.log1p(post.share_count)   * 0.05 +
        math.log1p(post.comment_count) * 0.03 +
        math.log1p(post.bookmark_count)* 0.03
    ) / VOLUME_NORMALIZER   # normalise về [0, 1]

    # ── 4. Personalisation: interest/location/field match ──────────────────────
    # ANN search: cosine similarity giữa user_interest_vector và post_content_vector
    interest_sim   = qdrant_cosine_sim(user_ctx.interest_vector, post.content_vector)
    location_match = compute_location_match(post.target_audience, user_ctx.location)
    # location_match ∈ {1.0 (same city), 0.8 (same country), 0.4 (other), 0.0 (excluded)}
    personal_score = interest_sim * 0.6 + location_match * 0.4

    # ── 5. Negative signals ────────────────────────────────────────────────────
    # hide/block: chỉ tính những event KHÔNG bị undo trong 24h
    hide_penalty     = min(post.active_hide_count   * 0.05, 0.30)
    block_penalty    = min(post.active_block_count  * 0.08, 0.40)
    report_penalty   = min(post.report_count        * 0.03, 0.20)
    # bot score của author
    bot_penalty      = post.bot_author_score * 0.30

    negative_total   = min(hide_penalty + block_penalty + report_penalty + bot_penalty, 0.70)

    # ── 6. Freshness decay ─────────────────────────────────────────────────────
    age_hours        = (now - post.created_at).total_seconds() / 3600
    freshness        = 1 / (1 + age_hours / FRESHNESS_HALF_LIFE)   # HALF_LIFE = 48h

    # ── 7. Final score ─────────────────────────────────────────────────────────
    raw = (
        engagement_score * 0.35 +
        volume_score     * 0.15 +
        personal_score   * 0.30 +
        freshness        * 0.20
    )
    return max(0.0, raw - negative_total)
```

### Diversity injection

Sau khi sort theo score, áp dụng **maximal marginal relevance** để tránh feed đơn điệu:

```python
def diversify(ranked: List[ScoredPost], lambda_=0.7) -> List[ScoredPost]:
    """
    Chọn lần lượt: ưu tiên post có score cao nhưng content KHÁC với đã chọn.
    lambda_ = 0.7: cân bằng relevance vs diversity.
    Chỉ áp dụng author diversity: cùng author không xuất hiện 2 lần trong 5 post liên tiếp.
    """
```

---

## Dwell-time signal pipeline

```
[post-interaction-service]:
  POST /api/v1/posts/{postId}/view
  Body: { duration_ms, scroll_depth_pct, viewport_pct }
  → Kafka: post.view.dwell { postId, userId, duration_ms, scroll_depth_pct }

[post-recommendation-service Kafka consumer]:
  Consume post.view.dwell
  → Redis: TS.ADD ts:post:dwell:{postId} * duration_ms

[DwellFlushJob mỗi 5 phút]:
  TS.RANGE ts:post:dwell:{postId} (last 1h)
  → tính avg_dwell_ms, p90_dwell_ms, scroll_stop_rate (sessions > 3s / tổng)
  → UPDATE post_signals SET avg_dwell_ms=?, scroll_stop_rate=?
  → UPDATE Redis post:rec-signals:{postId}
```

---

## Hide/Block undo handling

```
Kafka: post.hidden { postId, userId, timestamp }
  → INSERT hide_block_events (type=HIDE, is_undone=FALSE)
  → Redis: SADD post:hidden:{userId} {postId} EX 86400

Kafka: post.unhidden { postId, userId, timestamp }  (undo trong 24h)
  → UPDATE hide_block_events SET is_undone=TRUE WHERE post_id=? AND user_id=?
  → Redis: SREM post:hidden:{userId} {postId}
  Lý do: user có thể ẩn nhầm, hoặc tò mò ẩn rồi mở lại
  → Không tính vào điểm phạt khi is_undone=TRUE

Sau 24h: event được giữ nguyên (is_undone vẫn FALSE nếu không undo)
  → Tính vào active_hide_count khi scoring
```

---

## Kafka

### Published

| Topic | Payload | Consumer |
|-------|---------|----------|
| `post.recommendation.scored` | `{postId, score, signals_snapshot, model_version}` | ai-dashboard-svc (monitoring) |

### Consumed

| Topic | Action |
|-------|--------|
| `post.guard.completed` | Lưu guard_decision + target_audience vào post_signals |
| `post.interaction.created` | Cập nhật like/share/bookmark counts trong post_signals |
| `post.view.dwell` | Ghi vào Redis dwell-time buffer |
| `post.hidden` | Ghi hide event, cập nhật Redis window |
| `post.unhidden` | Đánh is_undone=TRUE, xoá khỏi Redis window |
| `post.deleted` | Xoá post khỏi Qdrant + đặt score = 0 |
| `user.interaction.updated` | Cập nhật user interest vector trong Qdrant + Redis |
| `ai.user.violation.suspected` | Cập nhật bot_author_score cho posts của user đó |
| `ai.model.updated` (post-guard) | Reload guard signal thresholds |

---

## gRPC (server)

```protobuf
syntax = "proto3";
package sssm.recommendation;
option java_package        = "io.github.lvoxx.proto.recommendation";
option java_multiple_files = true;

service PostRecommendationService {

  // Rerank candidate posts cho 1 user — called by post-service
  rpc RankPosts (RankPostsRequest) returns (RankPostsResponse);

  // Batch score mà không rerank — cho admin/analytics
  rpc ScorePosts (ScorePostsRequest) returns (ScorePostsResponse);
}

message CandidatePost {
  string post_id     = 1;
  string author_id   = 2;
  int64  created_at_ms = 3;
  int64  like_raw    = 4;
  int64  share_raw   = 5;
  int32  comment_count = 6;
  int64  view_raw    = 7;
}

message RankPostsRequest {
  string                 user_id    = 1;
  repeated CandidatePost candidates = 2;
  int32                  return_size = 3;   // default = 20
  string                 feed_type  = 4;    // HOME | EXPLORE | PROFILE
}

message RankPostsResponse {
  repeated string ranked_post_ids = 1;
  string          model_version   = 2;
  bool            is_fallback     = 3;   // true nếu service dùng fallback scoring
}

message ScorePostsRequest {
  repeated CandidatePost posts = 1;
  string user_id = 2;
}

message ScorePostsResponse {
  map<string, float> scores = 1;   // postId → score
}
```

---

## API (internal + admin)

```
# Internal (gọi bởi post-service qua gRPC, không expose public)
# Xem gRPC definition bên trên

# Admin / monitoring
GET  /api/v1/recommendation/model/status
     → { activeVersion, algorithm, auc, trainedAt }

GET  /api/v1/recommendation/post/{postId}/signals
     → full signal snapshot cho debug

GET  /api/v1/recommendation/experiments
     → list A/B experiment configs

POST /api/v1/recommendation/model/refresh    # ADMIN — trigger retrain
POST /api/v1/recommendation/cache/invalidate # ADMIN

GET  /api/v1/health
GET  /api/v1/metrics
```

---

## Model lifecycle (daily, 03:00 UTC — APScheduler)

```
1. Collect training data từ post_signals:
   - labels: CTR (click-through rate), dwell_time, share (positive) vs hide/block (negative)
   - features: tất cả signals trong post_signals table

2. Train LightGBM LTR (Learning-to-Rank) model
   - Experiment tracked trong MLflow
   - Objective: LambdaRank (listwise)

3. Nếu validation NDCG@20 > current Production model:
   transition → Staging → chạy shadow traffic evaluation
   Nếu shadow evaluation pass: transition → Production

4. Reload model in-process (không restart pod)
5. Re-embed tất cả active posts trong Qdrant (batch upsert)
6. Publish Kafka: post.recommendation.model.updated
```

---

## application.yaml

```yaml
sssm:
  recommendation:
    qdrant:
      host: ${QDRANT_HOST:localhost}
      grpc-port: ${QDRANT_GRPC_PORT:6334}
      post-collection: post_content
      user-collection: user_interests
      top-k: 50
    mlflow:
      tracking-uri: ${MLFLOW_TRACKING_URI:http://mlflow:5000}
      model-name: post-ranker
      model-stage: Production
    scoring:
      fallback-on-timeout: true
      timeout-ms: 400
      freshness-half-life-hours: 48
      target-dwell-ms: 8000
      bot-score-hard-threshold: 0.85
    dwell-flush:
      interval-seconds: 300
    hide-window-ttl-seconds: 86400
    minio:
      endpoint: ${MINIO_ENDPOINT:minio:9000}
      bucket: mlflow-artifacts
```

---

## Source layout

```
post-recommendation-service/
└── app/
    ├── main.py
    ├── config.py
    ├── dependencies.py
    ├── grpc/
    │   └── recommendation_servicer.py    # gRPC server impl
    ├── api/v1/routes/
    │   ├── admin.py
    │   └── signals.py
    ├── services/
    │   ├── ranking_service.py            # orchestrate scoring pipeline
    │   ├── signal_service.py             # read/write post_signals
    │   ├── dwell_service.py              # dwell buffer + flush
    │   ├── hide_block_service.py         # hide/block undo logic
    │   ├── diversity_service.py          # MMR post-processing
    │   └── interest_service.py           # user interest vector management
    ├── infrastructure/
    │   ├── database.py
    │   ├── qdrant.py
    │   ├── redis_feature_store.py
    │   ├── kafka.py
    │   ├── minio.py
    │   └── ml/
    │       ├── ranker.py                 # LightGBM LTR wrapper
    │       ├── embeddings.py             # sentence-transformers
    │       └── trainer.py
    └── scheduler/
        ├── model_refresh_job.py
        └── dwell_flush_job.py
```

---

## Key dependencies

```
fastapi==0.133          uvicorn[standard]==0.30
asyncpg==0.29           qdrant-client==1.12
redisvl==0.4            mlflow==2.18
sentence-transformers==3.1  lightgbm==4.5
grpcio==1.64            grpcio-tools==1.64
aiokafka==0.11          apscheduler==3.10
minio==7.2              numpy==2.0
prometheus-fastapi-instrumentator==7.0
opentelemetry-sdk==1.25
```

---

## Tests

- **Unit:** `test_ranking_service.py`, `test_scoring_model.py`, `test_hide_undo.py`, `test_diversity.py`
- **Integration:** PostgreSQL + Qdrant + Redis + Kafka (Testcontainers)
- **Automation:** feed request → candidate ranking → diversity check → fallback timeout test → hide/undo cycle → model refresh
