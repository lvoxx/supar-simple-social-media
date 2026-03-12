# media-guard-service

**Type:** FastAPI · Port `8091`  
**Language:** Python 3.12

---

## AI-native storage architecture

| Layer | Technology | Role |
|-------|-----------|------|
| Operational store | None — **stateless service** | No persistent business data; all decisions are logged by the caller (media-service) |
| Inference result cache | **Redis Stack + RedisVL** (perceptual-hash key + SHA-256) | Exact-hash dedup for identical re-uploads; avoids redundant GPU inference |
| Model registry | **MLflow Model Registry** (shared MLflow instance, PostgreSQL + MinIO backend) | Version and stage control for NSFW classifier and deepfake detector |
| Artifact store | **MinIO** | Model weights (CLIP, FaceForensics++), ONNX exports |

### Why stateless with Redis cache

media-guard-service is intentionally stateless — it performs safety analysis and returns a verdict. Persistent audit logs are the caller's responsibility (media-service stores `rejection_reason` in `media_assets`). A Redis cache keyed on `SHA-256(file_content)` and `pHash(image)` means identical or near-identical re-uploads (same file uploaded by different users) skip GPU inference entirely — critical for cost and latency at scale.

---

## DB init (K8S only)

| Engine | K8S resource | What it does |
|--------|-------------|-------------|
| MLflow | `Job` | `mlflow db upgrade` (shared MLflow tracking schema, if not already run) |
| MinIO | `Job` | Create `mlflow-artifacts` bucket |

Scripts: `infrastructure/k8s/db-init/media-guard-service/`

---

## Safety checks

| Check | Model / Method | Output |
|-------|---------------|--------|
| Magic-byte validation | File header vs declared extension | `valid: bool` |
| MIME type | `python-magic` (libmagic) | `valid: bool` |
| File entropy | Shannon entropy per 4 KB block | `normal: bool`, `entropy_score` |
| NSFW classification | **CLIP** (`openai/clip-vit-base-patch32`) embedding → logistic head fine-tuned on NSFW dataset | `nsfw_score: float [0–1]` |
| Deepfake detection | **FaceForensics++** CNN frequency-domain analyser | `deepfake_suspected: bool`, `confidence` |
| Malware heuristic | YARA rules + entropy spike detection | `malware_suspected: bool` |

---

## Inference cache (RedisVL)

```python
from redisvl.extensions.llmcache import SemanticCache
from redisvl.index import SearchIndex

# Exact-hash cache (SHA-256 of raw bytes) — no false positives
EXACT_CACHE_PREFIX = "media_guard:exact:"
EXACT_CACHE_TTL    = 86400   # 24 h

# pHash cache (perceptual hash, Hamming distance ≤ 8) — near-duplicate images
# Implemented with a RedisVL flat index on pHash binary vector
PHASH_CACHE_TTL    = 43200   # 12 h

async def check_exact(file_sha256: str) -> GuardResult | None:
    raw = await redis.get(f"{EXACT_CACHE_PREFIX}{file_sha256}")
    return GuardResult.model_validate_json(raw) if raw else None

async def store_exact(file_sha256: str, result: GuardResult):
    await redis.setex(f"{EXACT_CACHE_PREFIX}{file_sha256}", EXACT_CACHE_TTL,
                      result.model_dump_json())
```

---

## Inference pipeline

```
POST /api/v1/guard/media  { mediaId, tempUrl, contentType, fileSizeBytes }
  ↓
[1] Download file from tempUrl (presigned S3 / Cloudinary)
[2] Compute SHA-256 + pHash(image only)
[3] Redis exact-hash lookup
    HIT  → return cached verdict (< 5 ms)
    MISS ↓
[4] Parallel execution:
    ├── magic-byte + MIME check   (CPU)
    ├── entropy analysis          (CPU)
    ├── YARA malware scan         (CPU)
    ├── CLIP NSFW classification  (GPU / CPU fallback)
    └── FaceForensics++ deepfake  (GPU / CPU fallback, images & video only)
[5] Aggregate results → single GuardResult
[6] Store in Redis (exact + pHash)
[7] Return response
```

---

## MLflow model registry (shared instance)

```python
import mlflow.pytorch

# Load active model version on startup
nsfw_model   = mlflow.pytorch.load_model("models:/media-guard-nsfw/Production")
deepfake_model = mlflow.pytorch.load_model("models:/media-guard-deepfake/Production")
```

Models registered under:
- `media-guard-nsfw` — CLIP + logistic head fine-tuned on labelled NSFW dataset
- `media-guard-deepfake` — FaceForensics++ CNN, updated quarterly

Model promotion workflow follows the same `Development → Staging → Production → Archived` pattern as other AI services. Promotion is gated on accuracy + false-positive rate metrics logged to MLflow.

---

## application.yaml

```yaml
sssm:
  media-guard:
    redis:
      exact-cache-ttl-seconds: 86400
      phash-cache-ttl-seconds: 43200
    mlflow:
      tracking-uri: ${MLFLOW_TRACKING_URI:http://mlflow:5000}
      nsfw-model-name: media-guard-nsfw
      deepfake-model-name: media-guard-deepfake
      model-stage: Production
    minio:
      endpoint:   ${MINIO_ENDPOINT:minio:9000}
      access-key: ${MINIO_ACCESS_KEY}
      secret-key: ${MINIO_SECRET_KEY}
    thresholds:
      nsfw-reject:  0.85
      nsfw-flag:    0.60
      entropy-max:  7.2
    gpu:
      device: ${CUDA_DEVICE:cpu}
      batch-size: 8
```

---

## API

```
POST /api/v1/guard/media
     { mediaId, tempUrl, contentType, fileSizeBytes }
     → { safe, categories, confidence, checks: { magic, mime, entropy, nsfw, deepfake, malware } }

POST /api/v1/guard/file
     multipart/form-data  (raw bytes, max 50 MB)
     → { safe, magicValid, entropyNormal, mimeValid }

GET  /api/v1/guard/model/status
     → { nsfwVersion, deepfakeVersion, nsfwAccuracy, deepfakeAccuracy }

GET  /api/v1/guard/cache/stats
     → { exactHitRate, phashHitRate, totalRequests }

POST /api/v1/guard/cache/flush          # ADMIN
POST /api/v1/guard/model/reload         # ADMIN — hot-swap to latest Production model

GET  /api/v1/health
GET  /api/v1/metrics
```

---

## Source layout

```
media-guard-service/
└── app/
    ├── main.py
    ├── config.py
    ├── dependencies.py
    ├── api/v1/routes/
    │   ├── guard.py
    │   └── model.py
    ├── services/
    │   ├── guard_service.py        # orchestrates all checks in parallel
    │   ├── file_inspector.py       # magic, MIME, entropy, YARA
    │   └── model_service.py        # MLflow load, hot-swap
    ├── infrastructure/
    │   ├── redis.py                # exact-hash + pHash cache
    │   └── ml/
    │       ├── clip_nsfw.py        # CLIP inference
    │       ├── deepfake_detector.py
    │       └── yara_scanner.py
    └── scheduler/
        └── model_reload_job.py     # daily check for new Production model
```

---

## Key dependencies

```
fastapi==0.133          uvicorn[standard]==0.30
python-magic==0.4       yara-python==4.5
Pillow==10.4            imagehash==4.3      # pHash
transformers==4.44      torch==2.3          # CLIP, FaceForensics++
mlflow==2.18            redisvl==0.4
minio==7.2              httpx==0.27
prometheus-fastapi-instrumentator==7.0
opentelemetry-sdk==1.25
```

---

## Tests

- **Unit:** `test_file_inspector.py`, `test_nsfw_classifier.py`, `test_deepfake_detector.py`
- **Integration:** Redis container + WireMock (presigned URL) + MLflow mock
- **Automation:** safe image · NSFW image · polyglot file · deepfake video · exact-hash cache hit
