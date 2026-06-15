# Architecture

SSSM is an X-like social platform sized for **~10k–100k users** on a **$1–3k/month** budget.
The budget is the primary design driver: self-host what is cheap to operate, use managed
services only where ops pain outweighs cost, and eliminate egress fees.

## Topology

```
                 ┌──────── Cloudflare (WAF / DDoS / CDN / cache) ────────┐
   Web (Vue)─────┤                                                        │
   Mobile (Flutter)┤                                media → Cloudflare R2 │ (zero egress)
                 ▼                                  imgproxy / HLS        ▼
            AWS ALB → NGINX Ingress (EKS: 3× t3.large, spot/savings)
                 │
   ┌─────────────┴──────────────────────────────┬─────────────────────────────┐
   │ Spring Boot (Java 25) — CRUD/tx/admin       │ Gin (Go 1.26.3) — hot path   │
   │ user · post · media · ad                    │ timeline · recommendation    │
   │ admin-console · ad-console · creator-dash   │ notification · messaging     │
   │                                             │ engagement · search          │
   └─────────────┬───────────────────────────────┴──────────────┬──────────────┘
                 │ transactional outbox                          │
            Kafka 4.1 (KRaft, self-hosted) — event backbone ─────┘
                 │
   Postgres 17 (RDS, source of truth) · Redis 8 (cache/counters/feeds)
   Cassandra/Scylla (materialized feeds, P2) · OpenSearch (search)
   pgvector (rec embeddings) · ClickHouse (analytics, P4) · Keycloak (OIDC)
```

## Language split

| Spring Boot (Java 25) | Gin (Go 1.26.3) |
|---|---|
| Transactional, relational, money | High fan-out, low-latency, streaming |
| `user`, `post`, `media`, `ad` | `timeline`, `recommendation`, `notification` |
| `admin-console`, `ad-console` (server-side MVC) | `messaging`, `engagement`, `search` |
| `creator-dashboard` (SSR analytics) | goroutine concurrency = cheaper per request |

See [adr/0002-spring-and-gin-language-split.md](adr/0002-spring-and-gin-language-split.md).

## Data stores

| Store | Purpose |
|---|---|
| Postgres 17 (RDS) | Source of truth: users, posts, follows, ads, billing (posts partitioned) |
| Redis 8 | Cache, counters, recent timelines, rate limiting, pub/sub |
| Cassandra / ScyllaDB | Materialized home timelines (fan-out-on-write), Phase 2 |
| OpenSearch | Search (users, posts), trends |
| Kafka 4.1 | Event backbone via transactional outbox |
| Cloudflare R2 | Media blobs (zero egress) |
| pgvector → Qdrant | Recommendation embeddings |
| ClickHouse | Analytics / ads (Phase 4; Postgres matviews earlier) |

## Reuse strategy

- **Java** is a Maven reactor at `apps/java` (`pom.xml` is the parent): services are Spring
  Initializr modules; shared code lives in `apps/java/starters/*` (Spring Boot starters, e.g.
  `sssm-postgres-starter`) and `apps/java/common/*` (libraries, e.g. `sssm-common-core`). Shared
  versions and plugins are set once in the parent POM.
- **Go** is a single module at `apps/go` (`go.mod`): each service is a `package main` subdir
  (e.g. `timeline-service/`); shared packages live under `apps/go/common/*` (e.g. `common/httpx`).
  One `go.mod` = one place to manage dependencies.
- **Cross-language glue**: event/DTO schemas live once as **Protobuf in `schemas/`**, codegen for
  both Java and Go. One source of truth; services cannot drift.

## Media pipeline

Client → presigned URL → **direct upload to R2** (never through app servers) → R2 event → Kafka →
transcode worker. Images via `imgproxy` (AVIF/WebP). Video via `ffmpeg` → HLS adaptive bitrate,
run on Vast.ai GPU bursts or spot EC2. Served through Cloudflare.

## Recommendations (mapped from X's algorithm)

- **Candidate generation**: in-network (follows) + out-of-network ANN over pgvector embeddings
  (≈ SimClusters / TwHIN) + trends.
- **Light ranker**: GBDT/logistic in Go; real-time engagement features from Redis + Kafka
  streams (≈ GraphJet).
- **Heavy ranker** (P3+): small NN via ONNX-in-Go or FastAPI on cheap compute.
- **Home Mixer**: blends in/out-network + ads + who-to-follow; diversity + visibility filters.

## CI/CD & QA

GitHub Actions (free for public repos): build → unit → Testcontainers integration → lint →
CodeQL SAST → Trivy image scan → push to GHCR → ArgoCD sync. Infra via Terraform with
tfsec/checkov. QA layers: unit → integration → contract (Pact / Spring Cloud Contract) →
Playwright E2E → k6/Gatling load tests on Go hot paths → ZAP DAST at GA.

## Cost discipline

See the project memory `sssm-cost-model`. Key traps avoided: NAT Gateway data fees (use NAT
instance + VPC endpoints), S3/CloudFront egress (use R2 + Cloudflare), RDS Multi-AZ doubling
(start single-AZ), MSK (self-host Kafka KRaft at this scale).
