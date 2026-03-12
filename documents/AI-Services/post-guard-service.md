# post-guard-service

**Type:** FastAPI · Port `8090`  
**Language:** Python 3.12

---

## AI-native storage architecture

| Layer | Technology | Role |
|-------|-----------|------|
| Operational store | **PostgreSQL** (asyncpg) | Moderation results, human-review labels, training job metadata |
| Vector store | **Qdrant** | RAG — HNSW index of known violation embeddings for similarity retrieval |
| Semantic cache | **Redis Stack + RedisVL** (`langcache-embed-v1`) | Embedding-based cache — avoids re-running model on semantically identical inputs |
| Experiment tracking | **MLflow** (PostgreSQL backend + MinIO artifacts) | Experiment runs, metric comparison, training lineage |
| Model registry | **MLflow Model Registry** | Version control, stage promotion (`Development → Staging → Production → Archived`) |
| Artifact store | **MinIO** | BERT checkpoints, ONNX exports, training datasets |

### Why these choices

**Qdrant** is a Rust-native vector engine with HNSW + gRPC keeping p99 latency under 10 ms at millions of vectors. Its JSON payload filters allow querying by `category`, `severity`, and `recency` in a single round-trip — essential for dynamic RAG context assembly during inline moderation.

**Redis Stack + RedisVL semantic cache** goes beyond exact-match caching: it embeds each input with `langcache-embed-v1` (a cache-optimised fine-tuned sentence-transformer) and computes cosine distance. If distance ≤ threshold, the cached decision is returned in milliseconds — BERT never runs. This is especially effective for spam/phishing where variants of the same template are submitted repeatedly.

**MLflow** is the standard open-source experiment ledger. Its Model Registry provides named model versioning, stage transitions, and a UI for comparing runs — all self-hosted with no cloud vendor lock-in. MinIO provides the S3-compatible artifact backend.

---

## DB init (K8S only)

| Engine | K8S resource | Tool | What it does |
|--------|-------------|------|-------------|
| PostgreSQL | `Job` | `psql` | Create schema, tables |
| Qdrant | `Job` | Qdrant REST API (`curl`) | Create `post_violations` collection, set HNSW config |
| MLflow | `Job` | `mlflow db upgrade` | Initialise MLflow tracking schema in PostgreSQL |
| MinIO | `Job` | `mc` (MinIO CLI) | Create `mlflow-artifacts` bucket |

Scripts: `infrastructure/k8s/db-init/post-guard-service/`

---

## PostgreSQL schema

```sql
-- moderation_results  (audit log + human-review training data)
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
post_id          UUID        NOT NULL
content          TEXT        NOT NULL
content_hash     TEXT        NOT NULL       -- SHA-256 for exact-match dedup
qdrant_point_id  TEXT                       -- reference to Qdrant point
decision         VARCHAR(20) NOT NULL       -- APPROVED | FLAGGED | REJECTED
categories       TEXT[]
confidence       FLOAT       NOT NULL
model_version    VARCHAR(60) NOT NULL
human_reviewed   BOOLEAN     NOT NULL DEFAULT FALSE
human_decision   VARCHAR(20)
human_reviewer   UUID
reviewed_at      TIMESTAMPTZ
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

---

## Qdrant collection: `post_violations`

```python
from qdrant_client.models import VectorParams, Distance, HnswConfigDiff

client.create_collection(
    collection_name="post_violations",
    vectors_config=VectorParams(size=384, distance=Distance.COSINE),
    hnsw_config=HnswConfigDiff(m=16, ef_construct=200)
)

# Each point payload:
# { content_snippet, decision, categories, confidence,
#   model_version, created_at }
```

---

## Semantic cache (RedisVL)

```python
from redisvl.extensions.llmcache import SemanticCache
from redisvl.utils.vectorize import HFTextVectorizer

cache = SemanticCache(
    name="post_guard",
    redis_url=settings.redis_url,
    vectorizer=HFTextVectorizer("sentence-transformers/langcache-embed-v1"),
    distance_threshold=0.10,   # tight threshold — moderation must not over-generalise
    ttl=3600                   # 1 h — spam patterns shift daily
)

async def check_or_run(content: str) -> GuardResult:
    hit = await cache.acheck(content)
    if hit:
        metrics.cache_hits.inc()
        return GuardResult(**hit[0]["metadata"])

    result = await run_pipeline(content)
    await cache.astore(content, result.model_dump())
    metrics.cache_misses.inc()
    return result

# On human review override — invalidate stale cache entry
async def invalidate(content: str):
    await cache.adelete(content)
```

---

## Inference pipeline

```
Input text
  ↓
[1] Redis semantic cache lookup
    HIT  → return cached decision (< 5 ms)
    MISS ↓

[2] sentence-transformers/all-MiniLM-L6-v2  (384-dim embedding)

[3] Qdrant ANN search — post_violations collection
    top-5 similar known violations (payload: decision, categories)
    filter: { created_at: { gte: "90 days ago" } }

[4] BERT fine-tuned classifier
    input: content + RAG context from step 3
    output: { decision, categories, confidence }

[5] Cache store (RedisVL)
[6] Persist to PostgreSQL
[7] Return response
```

---

## MLflow experiment tracking

```python
import mlflow

with mlflow.start_run(experiment_id=EXPERIMENT_ID, run_name=f"bert_guard_{date}"):
    mlflow.log_params({
        "base_model":       "bert-base-uncased",
        "learning_rate":    2e-5,
        "epochs":           3,
        "batch_size":       32,
        "train_samples":    len(train_data),
        "embedding_model":  "all-MiniLM-L6-v2"
    })
    mlflow.log_metrics({
        "accuracy":                 accuracy,
        "f1_macro":                 f1_macro,
        "precision":                precision,
        "recall":                   recall,
        "inference_latency_p99_ms": latency_p99
    })
    mlflow.pytorch.log_model(
        model,
        artifact_path="bert_guard",
        registered_model_name="post-guard-bert"
    )
```

---

## Model lifecycle (daily, 02:00 UTC — APScheduler)

```
1. Query moderation_results WHERE human_reviewed=true AND created_at > last_trained_at
2. Fine-tune BERT (3 epochs), log run to MLflow Tracking
3. If new f1_macro > current Production model:
     mlflow.transition_model_version_stage → "Staging"
4. Run validation suite on held-out labelled set
5. If validation passes:
     transition → "Production"
     old version → "Archived"
6. Reload model in-process (no service restart)
7. Publish Kafka: ai.model.updated { model, version, accuracy, f1 }
8. Incremental Qdrant upsert: embed new training samples → add points
9. Flush Redis semantic cache: cache.aclear()
```

---

## application.yaml

```yaml
sssm:
  post-guard:
    qdrant:
      host: ${QDRANT_HOST:localhost}
      grpc-port: ${QDRANT_GRPC_PORT:6334}
      collection: post_violations
      top-k: 5
      score-threshold: 0.75
    semantic-cache:
      distance-threshold: 0.10
      ttl-seconds: 3600
      namespace: post_guard
    mlflow:
      tracking-uri: ${MLFLOW_TRACKING_URI:http://mlflow:5000}
      model-name: post-guard-bert
      model-stage: Production
    decision:
      approve-threshold: 0.90
      reject-threshold: 0.70
    minio:
      endpoint: ${MINIO_ENDPOINT:minio:9000}
      access-key: ${MINIO_ACCESS_KEY}
      secret-key: ${MINIO_SECRET_KEY}
      bucket: mlflow-artifacts
```

---

## Kafka

| Direction | Topic | Trigger | Consumers |
|-----------|-------|---------|-----------|
| Published | `ai.model.updated` | After model promotion | ai-dashboard-svc |
| Published | `post.moderation.flagged` | Decision = FLAGGED | ai-dashboard-svc |

---

## API

```
POST /api/v1/guard/post
     { postId, content, authorId, groupId? }
     → { decision, reason, confidence, categories, modelVersion }

POST /api/v1/guard/batch
     { items: [{postId, content, authorId}] }

GET  /api/v1/guard/model/status
     → { activeVersion, accuracy, f1Macro, trainedAt, trainingSamples }

GET  /api/v1/guard/cache/stats
     → { hitRate, totalHits, totalMisses, cachedEntries }

POST /api/v1/guard/cache/invalidate   # ADMIN — clear on policy change
POST /api/v1/guard/model/refresh      # ADMIN — trigger immediate retrain

GET  /api/v1/health
GET  /api/v1/metrics
```

---

## Source layout

```
post-guard-service/
└── app/
    ├── main.py
    ├── config.py               # Pydantic Settings
    ├── dependencies.py         # db pool, qdrant, semantic cache, mlflow client
    ├── api/v1/routes/
    │   ├── guard.py
    │   └── model.py
    ├── services/
    │   ├── guard_service.py    # orchestrates cache → RAG → BERT → store
    │   ├── rag_service.py      # Qdrant retrieval + context assembly
    │   └── model_service.py    # MLflow load, hot-swap, version management
    ├── infrastructure/
    │   ├── database.py         # asyncpg pool
    │   ├── qdrant.py           # QdrantAsyncClient wrapper
    │   ├── semantic_cache.py   # RedisVL SemanticCache wrapper
    │   ├── kafka.py            # aiokafka producer
    │   ├── minio.py            # MinIO client for artifact fetch
    │   └── ml/
    │       ├── bert_classifier.py
    │       ├── embeddings.py   # sentence-transformers
    │       └── trainer.py      # fine-tune pipeline + MLflow logging
    └── scheduler/
        └── model_refresh_job.py
```

---

## Key dependencies

```
fastapi==0.133          uvicorn[standard]==0.30
asyncpg==0.29           qdrant-client==1.12
redisvl==0.4            mlflow==2.18
sentence-transformers==3.1  transformers==4.44
torch==2.3              scikit-learn==1.5
aiokafka==0.11          apscheduler==3.10
minio==7.2              prometheus-fastapi-instrumentator==7.0
opentelemetry-sdk==1.25
```

---

## Tests

- **Unit:** `test_guard_service.py` (mock Qdrant + Redis), `test_rag_service.py`, `test_trainer.py`
- **Integration:** PostgreSQL + Qdrant + Redis containers (testcontainers-python), Kafka
- **Automation:** approve / flag / reject scenarios · semantic cache hit test · model refresh cycle
