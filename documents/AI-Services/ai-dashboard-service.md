# ai-dashboard-service

**Type:** FastAPI · Port `8093`  
**Language:** Python 3.12

---

## AI-native storage architecture

| Layer | Technology | Role |
|-------|-----------|------|
| Operational store | **PostgreSQL** (asyncpg) | Moderation queue, human-review audit log, alert rules |
| Aggregated insights | **Redis Stack** (RedisJSON + RedisTimeSeries) | Pre-aggregated KPIs read by dashboard; real-time metric counters; alert state |
| Model registry (read-only) | **MLflow Model Registry** (shared instance) | Query model versions, metrics, stage for the Models panel |
| Real-time push | **WebSocket** over Redis Pub/Sub | Cross-pod live dashboard streaming |

### Why Redis Stack as the insights layer

The dashboard front-end requires sub-second refreshes of KPIs (flagged today, queue depth, bot detections this hour). Computing these ad-hoc from PostgreSQL on every poll is expensive. Instead, ai-dashboard-service maintains **pre-aggregated metrics in RedisTimeSeries** (updated on every Kafka event) and **cached JSON snapshots in RedisJSON** (TTL 30 s for overview, 5 min for trends). The PostgreSQL store is only queried for exact audit trails and paginated moderation queue items.

---

## DB init (K8S only)

| Engine | K8S resource | What it does |
|--------|-------------|-------------|
| PostgreSQL | `Job` | `psql` — create schema and tables |
| Redis Stack | `Job` | Ensure `RedisTimeSeries` and `RedisJSON` modules loaded |

Scripts: `infrastructure/k8s/db-init/ai-dashboard-service/`

---

## PostgreSQL schema

```sql
-- moderation_queue
id              UUID        PRIMARY KEY DEFAULT gen_random_uuid()
source_service  VARCHAR(40) NOT NULL    -- post-guard | media-guard | user-analysis
entity_id       UUID        NOT NULL
entity_type     VARCHAR(20) NOT NULL    -- POST | MEDIA | USER
ai_decision     VARCHAR(20)
ai_confidence   FLOAT
categories      TEXT[]
status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                        -- PENDING | IN_REVIEW | APPROVED | REJECTED | ESCALATED
assigned_to     UUID
reviewed_by     UUID
review_action   VARCHAR(20)
review_reason   TEXT
reviewed_at     TIMESTAMPTZ
escalated_to    UUID
created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at      TIMESTAMPTZ

-- review_audit_log  (append-only)
id              UUID        PRIMARY KEY DEFAULT gen_random_uuid()
queue_item_id   UUID        NOT NULL
reviewer_id     UUID        NOT NULL
action          VARCHAR(20) NOT NULL    -- APPROVE | REJECT | ESCALATE | ASSIGN
reason          TEXT
created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

-- alert_rules
id              UUID        PRIMARY KEY DEFAULT gen_random_uuid()
metric          VARCHAR(60) NOT NULL    -- bot_detections_per_hour | queue_depth | …
threshold       FLOAT       NOT NULL
operator        VARCHAR(5)  NOT NULL    -- gt | lt | gte | lte
notification_target  TEXT              -- Slack webhook URL / email
is_active       BOOLEAN     NOT NULL DEFAULT TRUE
created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

---

## Redis Stack feature usage

```python
# RedisTimeSeries — real-time KPI counters (1-min buckets, 7-day retention)
# Updated via Kafka consumer on every relevant event

# Examples:
# ts:dashboard:flagged_posts          — incremented on ai.flagged events
# ts:dashboard:bot_detections         — incremented on ai.user.violation.suspected
# ts:dashboard:queue_depth            — gauge, updated on queue change
# ts:dashboard:model_accuracy:{name}  — gauge, updated on ai.model.updated

await redis.execute_command(
    "TS.ADD", "ts:dashboard:flagged_posts",
    "*", 1, "RETENTION", 604800000, "DUPLICATE_POLICY", "SUM"
)

# Dashboard overview snapshot (RedisJSON, TTL 30 s)
# Key: json:dashboard:overview
await redis.execute_command(
    "JSON.SET", "json:dashboard:overview", "$",
    json.dumps(overview_snapshot)
)
await redis.expire("json:dashboard:overview", 30)

# Trends query — last 24 h at 1-min granularity
samples = await redis.execute_command(
    "TS.RANGE", "ts:dashboard:flagged_posts",
    int((now - 86400) * 1000), int(now * 1000),
    "AGGREGATION", "sum", 60000
)
```

---

## MLflow integration (read-only)

```python
import mlflow

client = mlflow.MlflowClient(tracking_uri=settings.mlflow_tracking_uri)

# Models panel — list all registered models and their active versions
models = client.search_registered_models()
for model in models:
    versions = client.get_latest_versions(model.name, stages=["Production"])
    for v in versions:
        run = client.get_run(v.run_id)
        # Expose: version, accuracy, f1, trainedAt, trainingSamples

# No model training or promotion here — read-only
```

---

## Moderation review workflow

```
AI service flags content:
  post-guard publishes: post.moderation.flagged
  user-analysis publishes: ai.user.violation.suspected
  → ai-dashboard-service consumer inserts into moderation_queue
  → Increments ts:dashboard:queue_depth

Moderator opens dashboard, reviews queue:
  GET /api/v1/dashboard/moderation/queue

Moderator submits decision:
  PUT /api/v1/dashboard/moderation/{id}/review
    { action: APPROVE | REJECT | ESCALATE, reason: "..." }
  → UPDATE moderation_queue
  → INSERT review_audit_log
  → Decrement ts:dashboard:queue_depth
  → Publish Kafka: ai.moderation.decision
    → post-svc: restore / delete post
    → user-svc: clear / confirm violation flag

Alert check:
  After every queue mutation — evaluate alert_rules against current TS values
  If triggered → POST to notification_target (Slack / email)
```

---

## WebSocket protocol

```
Connect: WS /ws/dashboard/live  (requires role ADMIN or MODERATOR)

Server → Client  (pushed on every Kafka event):
  QUEUE_UPDATE     { queueSize, pendingByType }
  VIOLATION_ALERT  { userId, botScore, categories }
  METRIC_UPDATE    { metric, value, timestamp }
  MODEL_UPDATED    { modelName, version, accuracy, f1 }

Client → Server:
  PING
  SUBSCRIBE_METRIC { metric }
  UNSUBSCRIBE_METRIC { metric }
```

Cross-pod WS delivery: Redis Pub/Sub channel `dashboard:live` — all pod subscribers push to their connected WebSocket clients.

---

## Kafka

### Consumed

| Topic | Action |
|-------|--------|
| `post.moderation.flagged` | Insert into moderation_queue, push QUEUE_UPDATE |
| `ai.user.violation.suspected` | Insert into moderation_queue, push VIOLATION_ALERT, increment TS counter |
| `ai.model.updated` | Update RedisTimeSeries gauge, push MODEL_UPDATED |
| `message.notification.failed` | Log, push METRIC_UPDATE |

### Published

| Topic | Trigger | Consumers |
|-------|---------|-----------|
| `ai.moderation.decision` | Human review submitted | post-svc, user-svc, media-svc |

---

## API

```
GET  /api/v1/dashboard/overview
     → { queueDepth, flaggedToday, botDetectionsToday, avgConfidence, models }
     (from RedisJSON cache, TTL 30 s)

GET  /api/v1/dashboard/moderation/stats?period=1d|7d|30d
GET  /api/v1/dashboard/moderation/queue?status=PENDING&type=POST|MEDIA|USER&page=&size=
GET  /api/v1/dashboard/moderation/{id}
PUT  /api/v1/dashboard/moderation/{id}/review
POST /api/v1/dashboard/moderation/{id}/assign

GET  /api/v1/dashboard/users/violations?minScore=0.7&page=&size=
GET  /api/v1/dashboard/users/bots?minBotScore=0.8&page=&size=

GET  /api/v1/dashboard/models
     → list of registered models + active version metrics from MLflow

GET  /api/v1/dashboard/trends?metric=flagged_posts|bot_detections|queue_depth
                              &period=1h|6h|24h|7d
     (from RedisTimeSeries, aggregated 1-min buckets)

GET  /api/v1/dashboard/alerts
POST /api/v1/dashboard/alerts
PUT  /api/v1/dashboard/alerts/{id}
DELETE /api/v1/dashboard/alerts/{id}

WS   /ws/dashboard/live

GET  /api/v1/health
GET  /api/v1/metrics
```

---

## Source layout

```
ai-dashboard-service/
└── app/
    ├── main.py
    ├── config.py
    ├── dependencies.py
    ├── api/v1/routes/
    │   ├── dashboard.py
    │   ├── moderation.py
    │   ├── models.py
    │   └── alerts.py
    ├── services/
    │   ├── dashboard_service.py    # overview + trends from Redis
    │   ├── moderation_service.py   # queue CRUD + Kafka publish
    │   ├── model_service.py        # MLflow read-only queries
    │   └── alert_service.py        # rule evaluation
    ├── infrastructure/
    │   ├── database.py             # asyncpg
    │   ├── redis.py                # RedisTimeSeries + RedisJSON + Pub/Sub
    │   ├── kafka.py                # aiokafka consumer + producer
    │   └── websocket.py            # WS session registry + pub/sub fanout
    └── scheduler/
        └── metric_snapshot_job.py  # every 30 s: rebuild RedisJSON overview
```

---

## Key dependencies

```
fastapi==0.133          uvicorn[standard]==0.30
asyncpg==0.29           redis[hiredis]==5.0
redisvl==0.4            mlflow==2.18
aiokafka==0.11          apscheduler==3.10
websockets==13.0        prometheus-fastapi-instrumentator==7.0
opentelemetry-sdk==1.25
```

---

## Tests

- **Unit:** `test_dashboard_service.py`, `test_moderation_service.py`, `test_alert_service.py`
- **Integration:** PostgreSQL + Redis Stack + Kafka containers
- **Automation:** Kafka flag event → queue entry → human review → audit log · WS live push · trend query
