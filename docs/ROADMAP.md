# Roadmap & Build Checklist

Canonical, in-repo build plan for SSSM. Each phase is self-contained so it can be tackled (and
reviewed) independently. Status: `[ ]` todo · `[~]` in progress · `[x]` done.

> Services are created with their **official initializers** (Spring Initializr, `go mod init`,
> `npm create vue`, `flutter create`) under `apps/{java,go,vue,flutter}`. Shared code lives in
> `libs/`. Event/DTO contracts live once in `schemas/` (Protobuf, codegen for Java + Go).

## Cross-cutting (every phase)
- [ ] New Spring services generated via Spring Initializr; Go via `go mod init`; web/mobile via official CLIs
- [ ] Each service has a multi-stage `Dockerfile` (required for AWS/EKS deploys)
- [ ] Shared code reused from `apps/go/common/*` (Go) and `apps/java/{starters,common}/*` (Java); no copy-paste infra
- [ ] Events via Protobuf in `schemas/`; DB writes via transactional outbox
- [ ] CI: build → unit → integration (Testcontainers) → lint → SAST (CodeQL) → image scan (Trivy) → push (GHCR) → ArgoCD
- [ ] Coverage gate (≥70% → 80%); no critical CVEs; Terraform plan + tfsec/checkov on infra PRs
- [ ] Every endpoint: authn (Keycloak/OIDC) + rate-limit + structured log + OTel trace + Prometheus metric

## Phase 0 — Foundation (cost ~$0, local only)
- [x] Centralized layout: `apps/java` (Maven reactor), `apps/go` (single module), `schemas/`, `deploy/{terraform,helm}`, `docs/` — `libs/` removed
- [x] Spring Boot `user-service` (Spring Initializr, Boot 4.1, Java 25) re-parented to reactor `sssm-java-parent`; consumes `sssm-postgres-starter` + `sssm-common-core`
- [x] Go `timeline-service` (`package main`) in single module `apps/go`; Gin server reusing `apps/go/common/httpx`
- [x] Single Go module `apps/go` (`go.mod`, Go 1.26.3); shared packages under `apps/go/common/*`
- [x] Dockerfiles for `user-service` (temurin 25) and `timeline-service` (distroless); `.dockerignore`s
- [x] Protobuf `schemas/` (`post_events.proto`) + `buf.yaml`/`buf.gen.yaml`
- [x] GitHub Actions `ci.yml` (java / go / proto); README, ARCHITECTURE, ADR-0001 (R2), ADR-0002 (lang split)
- [x] Java shared modules established: `starters/sssm-postgres-starter`, `common/sssm-common-core` (publish to GitHub Packages later)
- [ ] Frontends `apps/vue` + `apps/flutter` — generated via official CLIs AFTER the Go/Java backend is complete
- [ ] Local dev verified: `make up`; Keycloak realm `ssw-realm` import; Flyway baseline migration
- [ ] Pre-commit hooks, conventional commits, release-please

## Phase 1 — Core MVP (first cloud env)
- [ ] `user-service`: profile CRUD, follow/unfollow, Keycloak link
- [ ] `post-service` (Spring Initializr): post/thread CRUD, like/repost/bookmark (tx + outbox→Kafka), partitioning
- [ ] `timeline-service`: fan-out-on-read from Redis cache + Postgres; cursor pagination
- [ ] `media-service` (Spring Initializr) + imgproxy: presigned upload → R2 → AVIF/WebP variants (images)
- [ ] `apps/vue` web client: auth, compose post, home timeline, profile
- [ ] IaC: Terraform VPC (NAT instance/VPC endpoints), EKS, RDS PG single-AZ, R2, Cloudflare; Helm; ArgoCD
- [ ] Observability: Prometheus + Grafana + Loki + Tempo on cluster; dashboards + alerts
- [ ] QA: unit + Testcontainers; Pact/Spring-Cloud-Contract; Playwright happy-path; k6 smoke
- [ ] DEPLOY GATE: staging green → prod; rollback runbook verified

## Phase 2 — Scale & engagement
- [ ] Cassandra/Scylla materialized timelines; hybrid fan-out (write for normal, read for celebrities)
- [ ] `notification-service` (Go): WebSocket/SSE, Kafka consumer, push (FCM/APNs)
- [ ] `engagement-service` (Go): like/view/repost counters in Redis, periodic flush to PG
- [ ] `search-service` + OpenSearch 2.x (UPGRADE off ES 6.4.2); indexing via Kafka
- [ ] `messaging-service` (Go): DMs over WebSocket
- [ ] Video pipeline: presigned upload → R2 event → Kafka → ffmpeg (Vast.ai GPU/spot) → HLS → Cloudflare
- [ ] Autoscaling: HPA + KEDA (Kafka lag); PgBouncer; caching strategy
- [ ] QA: k6/Gatling load tests; contract matrix; backup/restore drill

## Phase 3 — Recommendations (X-algorithm inspired)
- [ ] `recommendation-service` (Go): candidate gen — in-network + out-of-network (ANN/pgvector) + trends
- [ ] Embedding batch job (Vast.ai GPU nightly) → pgvector (≈ SimClusters/TwHIN)
- [ ] Light ranker (GBDT/logistic) in Go; feature store via Redis + Kafka streams (≈ GraphJet)
- [ ] Home Mixer: blend in/out-network + ads + who-to-follow; diversity + visibility filters; dedup
- [ ] Heavy ranker (optional): ONNX-in-Go or FastAPI on cheap CPU/GPU
- [ ] A/B framework + offline eval (NDCG/AUC/CTR) + online metrics; shadow deploys
- [ ] QA: recsys eval harness, feature-pipeline tests, fairness/abuse checks

## Phase 4 — Monetization & admin surfaces
- [ ] `ad-service` (Spring): campaigns, budgets, targeting, pacing, billing (tx); Stripe
- [ ] `ad-console` (Spring MVC, server-side): campaign CRUD, creative review/approval, spend reports
- [ ] `admin-service` (Spring MVC, server-side): moderation queue, takedowns, user/role mgmt, feature flags
- [ ] `creator-dashboard` (SSR): impressions, engagement, follower growth, top posts, payouts
- [ ] Analytics: ClickHouse (or Postgres matviews early) fed from Kafka; ad attribution
- [ ] QA: financial reconciliation tests, RBAC tests, audit logging, PII handling

## Phase 5 — Hardening & GA
- [ ] Security: CodeQL SAST, OWASP ZAP DAST, Trivy, gitleaks, dependency review, pen test
- [ ] Reliability: RDS Multi-AZ + read replica, DR runbook, backup/restore drills, chaos tests
- [ ] SLOs + alerting + on-call; global rate limiting; spam/abuse/bot detection
- [ ] Compliance: GDPR export/delete, ToS, content policy, age gating, cookie consent
- [ ] Perf: Core Web Vitals budgets, media lazy-load, WCAG 2.2 AA accessibility
- [ ] FinOps: cost vs $1–3k budget review, rightsizing, Spot ratio, anomaly alerts
- [ ] Release: blue/green or canary deploys, feature flags, GA sign-off
