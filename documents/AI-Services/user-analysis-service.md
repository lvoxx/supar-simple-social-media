# user-analysis-service

**Type:** FastAPI · Port `8092`  
**Language:** Python 3.12

---

## AI-native storage architecture

| Layer | Technology | Role |
|-------|-----------|------|
| Operational store | **PostgreSQL** (asyncpg) | Behaviour events (time-series), analysis reports, violation flags |
| Vector store | **Qdrant** | User behaviour embedding index for anomaly clustering and nearest-neighbour bot detection |
| Feature store | **Redis Stack** (RedisJSON + RedisTimeSeries) | Real-time feature aggregation: per-user event counters, velocity windows, session state |
| Experiment tracking | **MLflow** (shared instance) | Training runs, hyperparameter sweeps, metric history |
| Model registry | **MLflow Model Registry** | Version and stage control for all ML models |
| Artifact store | **MinIO** | Model checkpoints, training datasets, ONNX exports |

### Why these choices

**Qdrant** stores dense behaviour embeddings (LSTM encoder output, 256-dim). At inference time, querying the 20 nearest neighbours in embedding space provides instant context for anomaly scoring — far more informative than rule-based thresholds alone. Payload filters (`role`, `account_age_days`) allow stratified queries so bot classifiers don't penalise new legitimate users.

**Redis Stack (RedisTimeSeries + RedisJSON)** is the AI feature store. RedisTimeSeries retains rolling event counts per user at 1-minute granularity (posts/min, follows/min, API calls/min), enabling sub-millisecond feature retrieval that ML models need at inference time. RedisJSON stores the latest computed feature vector per user, avoiding re-computation on every request. Standard Redis cache TTLs are still used for API response caching.

**MLflow** tracks all experiment runs (hyperparameter sweeps, dataset versions) and manages the model registry with stage-gated promotion. `Development → Staging → Production → Archived` ensures only validated models reach inference.

---

## DB init (K8S only)

| Engine | K8S resource | What it does |
|--------|-------------|-------------|
| PostgreSQL | `Job` | `psql` — create schema and tables |
| Qdrant | `Job` | Qdrant REST API — create `user_behavior` collection |
| Redis Stack | `Job` | Enable `RedisTimeSeries` and `RedisJSON` modules (via `redis.conf`) |
| MLflow | `Job` | `mlflow db upgrade` (shared instance, if not already run) |

Scripts: `infrastructure/k8s/db-init/user-analysis-service/`

---

## PostgreSQL schema

```sql
-- behavior_events  (append-only time-series)
id           UUID        PRIMARY KEY DEFAULT gen_random_uuid()
user_id      UUID        NOT NULL
event_type   VARCHAR(50) NOT NULL    -- post.created | comment.created | follow | login | …
payload      JSONB
session_id   UUID
ip_hash      TEXT
created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()

-- user_analysis_reports  (latest snapshot per user)
id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid()
user_id                 UUID        UNIQUE NOT NULL
bot_score               FLOAT       NOT NULL DEFAULT 0
anomaly_score           FLOAT       NOT NULL DEFAULT 0
violation_suspected     BOOLEAN     NOT NULL DEFAULT FALSE
violation_categories    TEXT[]
recommendation_signals  JSONB
last_analyzed_at        TIMESTAMPTZ
model_version           VARCHAR(60)
created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at              TIMESTAMPTZ
```

---

## Qdrant collection: `user_behavior`

```python
from qdrant_client.models import VectorParams, Distance, HnswConfigDiff

client.create_collection(
    collection_name="user_behavior",
    vectors_config=VectorParams(size=256, distance=Distance.COSINE),
    hnsw_config=HnswConfigDiff(m=16, ef_construct=100)
)

# Each point payload:
# { user_id, account_age_days, role, bot_score,
#   anomaly_score, violation_suspected, indexed_at }
```

---

## Redis feature store

```python
# RedisTimeSeries — rolling event velocity (1-min buckets, 24-h retention)
# Key: ts:user:{userId}:{event_type}
await redis.execute_command(
    "TS.ADD", f"ts:user:{user_id}:post.created",
    "*", 1, "RETENTION", 86400000, "LABELS", "user_id", user_id
)

# Query last 5 min window (bot velocity feature)
samples = await redis.execute_command(
    "TS.RANGE", f"ts:user:{user_id}:post.created",
    int((now - 300) * 1000), int(now * 1000)
)

# RedisJSON — latest feature vector (updated after every analysis run)
# Key: json:user:features:{userId}
await redis.execute_command(
    "JSON.SET", f"json:user:features:{user_id}", "$",
    json.dumps(feature_vector)
)

# Standard Redis KV — API response cache
# Key: cache:user:report:{userId}  TTL 5 min
```

---

## ML models

| Model | Algorithm | Input features | Output |
|-------|-----------|---------------|--------|
| Anomaly detector | Isolation Forest | Event velocity, hour-of-day, session length distribution | `anomaly_score [0–1]` |
| Bot classifier | Gradient Boosting (XGBoost) | 30+ features: follow velocity, like/post ratio, content diversity index, pHash similarity across posts | `bot_score [0–1]` |
| Session analyser | LSTM Autoencoder | Event sequence, inter-event timing | Reconstruction error → anomaly flag |
| Recommendation engine | ALS (implicit feedback, Spark MLlib) | User-item interaction matrix from post views, likes, follows | `recommendation_signals` (top-50 item IDs) |

---

## Inference pipeline

```
Kafka event received (e.g. post.created for user X)
  ↓
[1] Append to PostgreSQL behavior_events
[2] RedisTimeSeries: TS.ADD for event type
[3] Check if analysis due (last_analyzed_at > 5 min ago)
    NO → done
    YES ↓
[4] Fetch feature vector from RedisJSON (cache) or re-compute from TS queries
[5] Run Isolation Forest + XGBoost bot classifier in parallel
[6] Run LSTM session analyser if session_count delta > threshold
[7] Qdrant upsert: update user's behaviour embedding
[8] Qdrant ANN query: 20 nearest neighbours → neighbourhood context
[9] Aggregate scores → user_analysis_reports (upsert)
[10] If bot_score > 0.85 or violation_suspected:
       Publish Kafka: ai.user.violation.suspected
[11] Update RedisJSON feature cache
```

---

## MLflow experiment tracking

```python
with mlflow.start_run(run_name=f"xgboost_bot_{date}"):
    mlflow.log_params({
        "n_estimators": 500, "max_depth": 6,
        "learning_rate": 0.05, "feature_count": 30
    })
    mlflow.log_metrics({
        "auc_roc": auc_roc, "precision": precision,
        "recall": recall, "f1": f1, "false_positive_rate": fpr
    })
    mlflow.xgboost.log_model(model, "xgb_bot",
        registered_model_name="user-analysis-bot-classifier")
```

---

## Kafka

### Consumed

`user.profile.updated` · `post.created` · `post.liked` · `comment.created` · `message.sent` (metadata only) · `group.member.joined` · `user.followed`

### Published

| Topic | Trigger | Consumers |
|-------|---------|-----------|
| `ai.user.insights` | Periodic analysis complete | ai-dashboard-svc |
| `ai.user.violation.suspected` | bot_score > 0.85 | ai-dashboard-svc, user-svc |
| `ai.recommendation.ready` | ALS batch job complete | post-svc (feed ranking) |

---

## API

```
POST /api/v1/analysis/user/{userId}/trigger
GET  /api/v1/analysis/user/{userId}
GET  /api/v1/analysis/user/{userId}/timeline?from=&to=
GET  /api/v1/analysis/user/{userId}/features

GET  /api/v1/analysis/recommendations/{userId}

GET  /api/v1/analysis/models/status
POST /api/v1/analysis/models/refresh    # ADMIN — trigger retraining

GET  /api/v1/health
GET  /api/v1/metrics
```

---

## Source layout

```
user-analysis-service/
└── app/
    ├── main.py
    ├── config.py
    ├── dependencies.py
    ├── api/v1/routes/
    │   ├── analysis.py
    │   └── models.py
    ├── services/
    │   ├── analysis_service.py     # pipeline orchestration
    │   ├── feature_service.py      # RedisTimeSeries + RedisJSON feature retrieval
    │   └── model_service.py        # MLflow load, hot-swap
    ├── infrastructure/
    │   ├── database.py             # asyncpg
    │   ├── qdrant.py
    │   ├── redis.py                # RedisTimeSeries + RedisJSON + KV cache
    │   ├── kafka.py
    │   ├── minio.py
    │   └── ml/
    │       ├── isolation_forest.py
    │       ├── bot_classifier.py   # XGBoost
    │       ├── lstm_analyser.py
    │       └── als_recommender.py
    └── scheduler/
        ├── model_refresh_job.py
        └── als_batch_job.py        # weekly ALS retraining
```

---

## Key dependencies

```
fastapi==0.133          uvicorn[standard]==0.30
asyncpg==0.29           qdrant-client==1.12
redis[hiredis]==5.0     redisvl==0.4
mlflow==2.18            xgboost==2.1
torch==2.3              scikit-learn==1.5
implicit==0.7           # ALS
aiokafka==0.11          apscheduler==3.10
minio==7.2              numpy==1.26
prometheus-fastapi-instrumentator==7.0
opentelemetry-sdk==1.25
```

---

## Tests

- **Unit:** `test_bot_classifier.py`, `test_anomaly_detector.py`, `test_feature_service.py`
- **Integration:** PostgreSQL + Qdrant + Redis Stack + Kafka containers
- **Automation:** ingest events → trigger analysis → verify bot_score · recommendation pipeline · violation event published
