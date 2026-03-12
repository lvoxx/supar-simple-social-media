# comment-recommendation-service

**Type:** FastAPI · Port `8095`  
**Language:** Python 3.12

---

## AI-native storage architecture

| Layer | Technology | Role |
|-------|-----------|------|
| Operational store | **PostgreSQL** (asyncpg) | Scored comment snapshots, quality audit log, spam pattern store |
| Feature store | **Redis Stack** (RedisJSON + RedisTimeSeries) | Per-comment signal cache, per-user comment quality history, dwell-time buffers |
| Vector store | **Qdrant** | Comment embeddings — phát hiện spam/duplicate theo ngữ nghĩa + topic relevance |
| Experiment tracking | **MLflow** (shared instance) | Training runs, comment quality model versions |
| Model registry | **MLflow Model Registry** | Stage-gated promotion |
| Artifact store | **MinIO** | Classifier checkpoints, spam pattern datasets |

---

## Vị trí trong luồng dữ liệu

```
Client → GET /api/v1/posts/{postId}/comments?sort=BEST&cursor=&size=20
         ↓
    [comment-service]
    1. Query Cassandra → lấy candidate comments (size × 3 = 60 candidates từ trang đó)
    2. gRPC → comment-recommendation-service (RankComments)
       Input:  { postId, userId, candidates: [{commentId, authorId, content, counts...}] }
       Output: { rankedCommentIds: [top 20 ids] }
    3. Trả về 20 comments đã reranked
    4. Return to client

Với sort=NEWEST|OLDEST: bỏ qua recommendation (chronological order thuần túy)
Với sort=BEST: bắt buộc đi qua recommendation service
```

> **Fallback:** Timeout (> 350 ms) hoặc lỗi → comment-service fallback về `reaction_count DESC`.

---

## Tại sao comment cần recommendation riêng

Post-recommendation-service xử lý ranking post-level. Comment có đặc điểm khác:
- Số lượng comments trên 1 post có thể lên đến hàng nghìn → cần filter mạnh hơn.
- Comment spam, toxic, bot-comment có pattern hoàn toàn khác post spam.
- Reply-chain engagement là signal đặc thù của comment (một comment tốt kéo nhiều replies hay).
- View time trên comment ngắn hơn nhiều so với post → cần threshold riêng.

---

## DB init

K8S `InitContainer` + `Job`.  
Scripts: `infrastructure/k8s/db-init/comment-recommendation-service/`

---

## PostgreSQL schema (schema `sssm_comment_recommendation`)

```sql
-- comment_signals: snapshot tín hiệu mỗi comment
id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid()
comment_id           UUID        NOT NULL UNIQUE
post_id              UUID        NOT NULL
author_id            UUID        NOT NULL
depth                INT         NOT NULL DEFAULT 0
content_length       INT         NOT NULL DEFAULT 0
reaction_count       INT         NOT NULL DEFAULT 0     -- tổng tất cả reactions
reply_count          INT         NOT NULL DEFAULT 0
like_count           INT         NOT NULL DEFAULT 0     -- reaction type LIKE riêng
view_count           BIGINT      NOT NULL DEFAULT 0
avg_dwell_ms         INT         NOT NULL DEFAULT 0     -- thời gian đọc trung bình
scroll_stop_rate     FLOAT       NOT NULL DEFAULT 0
spam_score           FLOAT       NOT NULL DEFAULT 0     -- từ spam classifier
toxicity_score       FLOAT       NOT NULL DEFAULT 0     -- từ toxicity classifier
bot_author_score     FLOAT       NOT NULL DEFAULT 0     -- từ user-analysis-service
duplicate_score      FLOAT       NOT NULL DEFAULT 0     -- cosine distance với known spam
is_spam_confirmed    BOOLEAN     NOT NULL DEFAULT FALSE
reply_engagement_rate FLOAT      NOT NULL DEFAULT 0     -- reply_count / view_count
quality_score        FLOAT       NOT NULL DEFAULT 0     -- final composite score
last_scored_at       TIMESTAMPTZ
created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at           TIMESTAMPTZ

-- spam_patterns: known spam embeddings (denormalised từ Qdrant + human review)
id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid()
content_snippet      TEXT        NOT NULL
pattern_type         VARCHAR(50) NOT NULL    -- SPAM | TOXIC | BOT_GENERATED | DUPLICATE
qdrant_point_id      TEXT
confidence           FLOAT
reported_count       INT         NOT NULL DEFAULT 0
created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

---

## Qdrant collection: `comment_embeddings`

```python
client.create_collection(
    collection_name="comment_embeddings",
    vectors_config=VectorParams(size=384, distance=Distance.COSINE),
    hnsw_config=HnswConfigDiff(m=16, ef_construct=200)
)
# Payload per point:
# { comment_id, post_id, author_id, depth,
#   spam_score, toxicity_score, quality_score, created_at_ts }
```

ANN search được dùng cho 2 mục đích:
1. **Spam/duplicate detection**: so sánh comment mới với known spam patterns.
2. **Topic relevance**: cosine sim giữa comment embedding và post embedding → comment có liên quan đến post không.

---

## Redis feature store

```python
# Dwell-time buffer (từ comment-service gửi qua Kafka)
# Key: ts:comment:dwell:{commentId}  (RedisTimeSeries, 30 min retention)

# Per-comment signal cache (Redis Hash, TTL 3 min)
# Key: comment:rec-signals:{commentId}
# Fields: spam_score, toxicity_score, bot_author_score, quality_score

# Per-user comment quality history (Redis Sorted Set, TTL 24h)
# Key: user:comment-quality:{userId}
# Score = quality_score, Member = commentId
# Dùng để detect user thường xuyên post low-quality comments
```

---

## Scoring model

```python
def score_comment(comment: CandidateComment, post_ctx: PostContext) -> float:

    # ── 1. HARD FILTER ───────────────────────────────────────────────────────
    if comment.spam_score > 0.80:
        return 0.0
    if comment.toxicity_score > 0.85:
        return 0.0
    if comment.bot_author_score > 0.85:
        return 0.0
    if comment.is_spam_confirmed:
        return 0.0

    # ── 2. Quality signals ───────────────────────────────────────────────────
    # Reaction quality: nhiều reactions đa dạng = comment được đón nhận tốt
    reaction_score  = math.log1p(comment.reaction_count) / LOG_NORMALIZER

    # Reply engagement: comment kéo nhiều replies hay = discussion quality
    reply_score     = math.log1p(comment.reply_count) * comment.reply_engagement_rate

    # Dwell time: người đọc dừng lại đọc kỹ không?
    # TARGET_COMMENT_DWELL = 2000ms (comment ngắn hơn post)
    dwell_score     = min(comment.avg_dwell_ms / TARGET_COMMENT_DWELL_MS, 1.0)
    scroll_stop     = comment.scroll_stop_rate

    engagement_score = (
        reaction_score  * 0.30 +
        reply_score     * 0.20 +
        dwell_score     * 0.25 +
        scroll_stop     * 0.25
    )

    # ── 3. Content quality ───────────────────────────────────────────────────
    # Topic relevance: comment có liên quan đến nội dung post không?
    topic_sim       = qdrant_cosine_sim(comment.embedding, post_ctx.post_embedding)

    # Content length signal: quá ngắn (< 10 chars) hoặc quá dài = lower quality
    length          = comment.content_length
    length_score    = 1.0 if 20 <= length <= 500 else 0.5 if 10 <= length < 20 else 0.3

    # Duplicate penalty: gần giống comment đã có trong post
    duplicate_penalty = comment.duplicate_score * 0.4

    content_score = (
        topic_sim    * 0.5 +
        length_score * 0.5
    ) - duplicate_penalty

    # ── 4. Depth bonus: depth-0 comments nhận slight boost (dễ đọc hơn) ────
    depth_bonus = 0.05 if comment.depth == 0 else 0.0

    # ── 5. Negative signals ─────────────────────────────────────────────────
    spam_penalty     = comment.spam_score     * 0.5
    toxicity_penalty = comment.toxicity_score * 0.6
    bot_penalty      = comment.bot_author_score * 0.3

    negative = min(spam_penalty + toxicity_penalty + bot_penalty, 0.80)

    # ── 6. Freshness (comments cần fresh hơn posts) ─────────────────────────
    age_hours   = (now - comment.created_at).total_seconds() / 3600
    freshness   = 1 / (1 + age_hours / 12)   # half-life = 12h cho comments

    # ── 7. Final score ───────────────────────────────────────────────────────
    raw = (
        engagement_score * 0.40 +
        content_score    * 0.30 +
        freshness        * 0.20 +
        depth_bonus      * 0.10
    )
    return max(0.0, raw - negative)
```

---

## Comment classifier pipeline

```
Mỗi comment mới được tạo → comment-service publish Kafka: comment.created
  ↓
[comment-recommendation-service Kafka consumer]
  ↓
[1] Embed comment content (sentence-transformers/all-MiniLM-L6-v2)
[2] Qdrant ANN search: comment_embeddings
    → top-5 similar known spam/toxic patterns
    → duplicate_score = max cosine similarity với posts trong cùng post_id
[3] Spam classifier (BERT fine-tuned trên comment spam dataset)
    → spam_score ∈ [0, 1]
[4] Toxicity classifier (BERT fine-tuned)
    → toxicity_score ∈ [0, 1]
[5] Bot score: đọc từ Redis user:bot-score:{authorId} (feed bởi user-analysis-service)
[6] Lưu kết quả vào PostgreSQL comment_signals
[7] Upsert embedding vào Qdrant
[8] Cache Redis comment:rec-signals:{commentId}

Nếu spam_score > 0.70 → Kafka: comment.spam.detected → comment-service có thể auto-hide
```

---

## Dwell-time signal pipeline (comment)

```
[comment-service]:
  POST /api/v1/comments/{commentId}/view
  Body: { duration_ms }
  → Kafka: comment.view.dwell { commentId, postId, userId, duration_ms }

[comment-recommendation-service Kafka consumer]:
  → Redis: TS.ADD ts:comment:dwell:{commentId} * duration_ms

[DwellFlushJob mỗi 5 phút]:
  → tính avg_dwell_ms, scroll_stop_rate
  → UPDATE comment_signals
```

---

## Kafka

### Published

| Topic | Consumer |
|-------|---------|
| `comment.spam.detected` | comment-svc (auto-hide), ai-dashboard-svc |
| `comment.recommendation.scored` | ai-dashboard-svc (monitoring) |

### Consumed

| Topic | Action |
|-------|--------|
| `comment.created` | Chạy classification pipeline, tạo comment_signal record |
| `comment.reacted` | Cập nhật reaction_count trong comment_signals |
| `comment.view.dwell` | Ghi vào Redis dwell buffer |
| `comment.deleted` | Đặt quality_score = 0, xoá khỏi Qdrant |
| `ai.user.violation.suspected` | Cập nhật bot_author_score cho comments của user đó |

---

## gRPC (server)

```protobuf
syntax = "proto3";
package sssm.recommendation;

service CommentRecommendationService {

  // Rerank candidate comments — called by comment-service
  rpc RankComments (RankCommentsRequest) returns (RankCommentsResponse);
}

message CandidateComment {
  string comment_id       = 1;
  string author_id        = 2;
  int64  created_at_ms    = 3;
  int32  reaction_count   = 4;
  int32  reply_count      = 5;
  int64  view_count       = 6;
  int32  depth            = 7;
  int32  content_length   = 8;
}

message RankCommentsRequest {
  string                   post_id    = 1;
  string                   user_id    = 2;
  repeated CandidateComment candidates = 3;
  int32                    return_size = 4;
}

message RankCommentsResponse {
  repeated string ranked_comment_ids = 1;
  string          model_version      = 2;
  bool            is_fallback        = 3;
}
```

---

## API (internal + admin)

```
GET  /api/v1/recommendation/comment/{commentId}/signals
POST /api/v1/recommendation/comment/classify     # manual trigger (ADMIN)
POST /api/v1/recommendation/model/refresh        # ADMIN — retrain
GET  /api/v1/health
GET  /api/v1/metrics
```

---

## Model lifecycle (daily, 04:00 UTC)

Tương tự post-recommendation-service nhưng với comment-specific labels (reaction, reply engagement, dwell).

---

## application.yaml

```yaml
sssm:
  comment-recommendation:
    qdrant:
      host: ${QDRANT_HOST:localhost}
      grpc-port: ${QDRANT_GRPC_PORT:6334}
      comment-collection: comment_embeddings
    mlflow:
      tracking-uri: ${MLFLOW_TRACKING_URI:http://mlflow:5000}
      model-name: comment-ranker
      model-stage: Production
    scoring:
      fallback-on-timeout: true
      timeout-ms: 350
      target-dwell-ms: 2000
      spam-hard-threshold: 0.80
      toxicity-hard-threshold: 0.85
      bot-hard-threshold: 0.85
    dwell-flush:
      interval-seconds: 300
    minio:
      endpoint: ${MINIO_ENDPOINT:minio:9000}
      bucket: mlflow-artifacts
```

---

## Key dependencies

(Tương tự post-recommendation-service — dùng chung base image)

```
fastapi uvicorn asyncpg qdrant-client redisvl mlflow
sentence-transformers transformers torch lightgbm
grpcio grpcio-tools aiokafka apscheduler minio numpy
prometheus-fastapi-instrumentator opentelemetry-sdk
```

---

## Tests

- **Unit:** `test_comment_scoring.py`, `test_spam_classifier.py`, `test_duplicate_detection.py`
- **Integration:** PostgreSQL + Qdrant + Redis + Kafka (Testcontainers)
- **Automation:** create comment → classify → rank → spam auto-hide → dwell flush → model refresh
