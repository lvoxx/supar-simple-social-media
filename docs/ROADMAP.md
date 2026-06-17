# Roadmap & Build Checklist

Canonical, in-repo build plan for SSSM. Each phase is self-contained so it can be tackled (and
reviewed) independently. Status: `[ ]` todo · `[~]` in progress · `[x]` done.

> Services are created with their **official initializers** (Spring Initializr, `go mod init`,
> `npm create vue`, `flutter create`) under `apps/{java,go,vue,flutter}`. Shared code lives in
> `apps/java/{starters,common}/*` (Java) and `apps/go/common/*` (Go). Event/DTO contracts live
> once in `schemas/` (Protobuf, codegen for Java + Go).
>
> **DB rule:** database schemas are owned and initialized by **infrastructure only**
> (`deploy/migrations/*` applied by a Flyway runner — `make migrate` locally, a k8s Job in cloud).
> Service apps NEVER migrate; they run with `ddl-auto=validate` and only read the schema.

## Cross-cutting (every phase)

- [ ] New Spring services generated via Spring Initializr; Go via `go mod init`; web/mobile via official CLIs
- [ ] Each service has a multi-stage `Dockerfile` (required for AWS/EKS deploys)
- [ ] Shared code reused from `apps/go/common/*` (Go) and `apps/java/{starters,common}/*` (Java); no copy-paste infra
- [ ] Events via Protobuf in `schemas/`; DB writes via transactional outbox
- [ ] CI: build → unit → integration (Testcontainers) → lint → SAST (CodeQL) → image scan (Trivy) → push (GHCR) → ArgoCD
- [ ] Coverage gate (≥70% → 80%); no critical CVEs; Terraform plan + tfsec/checkov on infra PRs
- [ ] Authn at the **gateway sidecar** (validates Keycloak/OIDC token, forwards `X-Auth-*` identity headers); services NEVER decode JWTs (ADR-0003). Every endpoint also: rate-limit + structured log + OTel trace + Prometheus metric
- [ ] Edge security mechanisms (gateway/Keycloak-owned, not per-service): brute-force protection (Keycloak), anti-spam/abuse (WAF + rate limit + Redis per-user write quotas), read/response caching (CDN + Redis)
- [ ] **Every new service registers with the platform in the same PR that adds it** (keeps app + infra in lock-step — this is the step every later phase must not skip): (1) add an entry to `deploy/helm/sssm-services` values — `port`, ingress `paths`, probe paths, and `metricsPath` — which then auto-gets a Deployment/Service/Ingress, a Prometheus **ServiceMonitor**, and **OTEL_*** tracing env via the umbrella chart; (2) it ships through the existing `sssm-services` ArgoCD app (no new Argo app needed for app services); (3) if it owns schema, add `deploy/migrations/<service>/` + a Flyway runner (`make migrate` / k8s Job); (4) confirm CI builds → scans (Trivy) → pushes its image to GHCR. Full checklist: `deploy/README.md` → "Adding a service".

## Phase 0 — Foundation (cost ~$0, local only)

- [x] Centralized layout: `apps/java` (Maven reactor), `apps/go` (single module), `schemas/`, `deploy/{terraform,helm}`, `docs/` — `libs/` removed
- [x] Spring Boot `user-service` (Spring Initializr, Boot 4.1, Java 25) re-parented to reactor `sssm-java-parent`; consumes `sssm-postgres-starter` + `sssm-common-core`
- [x] Go `timeline-service` (`package main`) in single module `apps/go`; Gin server reusing `apps/go/common/httpx`
- [x] Single Go module `apps/go` (`go.mod`, Go 1.26.3); shared packages under `apps/go/common/*`
- [x] Dockerfiles for `user-service` (temurin 25) and `timeline-service` (distroless); `.dockerignore`s
- [x] Protobuf `schemas/` (`post_events.proto`) + `buf.yaml`/`buf.gen.yaml`
- [x] GitHub Actions `ci.yml` (java / go / proto); README, ARCHITECTURE, ADR-0001 (R2), ADR-0002 (lang split)
- [x] Java shared modules established: `starters/sssm-postgres-starter`, `common/sssm-common-core` (publish to GitHub Packages later)
- [x] Infra-owned DB init: `deploy/migrations/user-service/V1__baseline.sql` (profiles + follows) applied by `docker/docker-compose.flyway.yml` via `make migrate`; app de-Flyway'd (starter ships no migration tooling, `ddl-auto=validate`)
- [x] `user-service` runtime config (`application.yml`): datasource (schema `sssm`), actuator/prometheus, port 8081 (authn moved to gateway sidecar — no in-service OIDC resource server, see ADR-0003)
- [x] Keycloak realm import `docker/keycloak/ssw-realm.json` (roles user/creator/admin, PKCE web client `sssm-web`, dev user)
- [x] Pre-commit hooks (`.pre-commit-config.yaml`: whitespace/yaml/json/gitleaks/gofmt), conventional commits (commit-msg hook), release-please (`release-please-config.json` + manifest + workflow)
- [ ] Frontends `apps/vue` + `apps/flutter` — generated via official CLIs AFTER the Go/Java backend is complete
- [~] Local dev RUNTIME verification (`make up && make migrate`, login via realm) — artifacts ready; not yet executed (needs Docker)

## Phase 1 — Core MVP (first cloud env)

- [x] `user-service`: profile CRUD, follow/unfollow, Keycloak link (profile CRUD, follow/unfollow with denormalized counts, cursor-paged follower/following, gateway-trusted identity; `mvn test` green — 13 unit/web/service tests; Testcontainers integration tests (`*IT`, run in CI `verify`) validate JPA mappings against the real infra migration + the follow/pagination flow)
- [x] `post-service` (Spring Initializr): post/thread CRUD, like/repost/bookmark (tx + outbox→Kafka), partitioning
  - [x] Slice 1: post/thread CRUD (create/read/delete, cursor-paged author timeline + replies, reply-count denorm, gateway-trusted identity), RANGE-partitioned `posts` migration, transactional outbox → Kafka relay emitting `PostCreated`/`PostDeleted` Protobuf (shared `sssm-events` module compiles `schemas/` via protoc). `mvn test` green — 11 unit/web/relay tests; Testcontainers `*IT` (CRUD + outbox payload + pagination) run in CI `verify`
  - [x] Slice 2: like/repost/bookmark engagement — idempotent toggle endpoints (`PUT`/`DELETE` `/api/v1/posts/{id}/{like,repost,bookmark}`, gateway-trusted actor), denormalized counts on `posts`, one `post_engagements` row per `(post, actor, kind)` (composite PK = idempotency), each add/remove emits a `PostEngagement` Protobuf via the transactional outbox (enum extended with UNREPOST/BOOKMARK/UNBOOKMARK). `V2__engagement.sql` migration; `mvn test` green — 7 unit + 4 web engagement tests; `EngagementFlowIT` (counts/idempotency/outbox payload) runs in CI `verify`
- [x] `timeline-service` (Go, `go mod init` subdir of `apps/go`): fan-out-on-read home feed — at read time it gathers the caller's follow graph (`sssm.follows`) plus self and merges their recent posts (`sssm.posts`) newest-first, with opaque keyset cursor pagination on `(created_at, id)`. Gateway-trusted identity via shared `httpx.AuthSubject` (`X-Auth-Subject`); read-only consumer of the shared `sssm` schema (Phase 2 swaps in materialized timelines). Redis fronts the two expensive reads (follow set + first page, short TTL, best-effort — degrades to Postgres on miss/outage); deep cursor pages bypass cache. `go test ./...` green — 10 unit tests (cursor round-trip/validation + fan-out service with fakes: pagination, self-inclusion, cache hit/write, cursored-page cache bypass, invalid-cursor 400)
- [x] `media-service` (Spring Initializr) + imgproxy: presigned upload → R2 → AVIF/WebP variants (images) — `POST /api/v1/media/uploads` mints a presigned PUT URL (AWS SDK v2 S3 against Cloudflare R2) for direct browser→R2 upload of the original; `POST /api/v1/media/{id}/complete` HEAD-verifies the object and promotes the row PENDING→READY (records real size/content-type, optional width/height hints); `GET /api/v1/media/{id}` (public) returns the record with signed imgproxy variant URLs (one per configured width × format, AVIF/WebP on-the-fly — the service stores only the original, no derived bytes). Gateway-trusted identity via `X-Auth-Subject`; image-only content-type allowlist; server-derived object keys. Infra-owned `media` migration (`V1__baseline.sql`) + `flyway-media` runner; `docker-compose.imgproxy.yml`. Lean Phase 1 slice — no outbox/Kafka yet (no consumer). `mvn test` green — 9 unit + 4 web tests (security wiring, content-type validation, lifecycle, imgproxy signing); Testcontainers `MediaFlowIT`/`MediaServiceApplicationIT` (JPA mapping vs real migration + PENDING→READY) run in CI `verify`
- [ ] `apps/vue` web client: auth, compose post, home timeline, profile
- [~] IaC: Terraform VPC (NAT instance/VPC endpoints), EKS, RDS PG single-AZ, R2, Cloudflare; Helm; ArgoCD — **authored, not yet applied** (no cloud account/binaries locally). `deploy/terraform` composes pinned registry modules (vpc `~>6`, eks `~>21`, rds `~>7`, `RaJiska/fck-nat` `~>1.6`, aws `~>6`, cloudflare `~>5`): 2-AZ VPC with an **fck-nat instance** + free S3 gateway endpoint (no NAT Gateway) + optional ECR/STS/Logs interface endpoints; **SPOT t3.large** EKS managed node group (v21 access-entry auth) + core add-ons; single-AZ **Postgres 17** (master password in Secrets Manager, reachable only from the node SG); Cloudflare **R2** media bucket + optional proxied app CNAME. S3 partial-config remote-state backend. GitOps skeleton in `deploy/argocd` (app-of-apps → `ingress-nginx` chart `4.15.1` + the in-repo `deploy/helm/sssm-services` umbrella chart that ranges over the 4 services) with ArgoCD bootstrapped from the upstream `argo-cd` chart `9.5.21`. See `deploy/README.md`. Remaining: apply to a real account; wire app env/secrets; gateway sidecar (ADR-0003)
- [~] Observability: Prometheus + Grafana + Loki + Tempo on cluster; dashboards + alerts — **authored as pinned ArgoCD apps** (not applied): `kube-prometheus-stack` `86.2.3` (Prometheus + Grafana + Alertmanager + default dashboards/alerts; Grafana pre-wired with Loki & Tempo datasources), `loki` `7.0.0` (single-binary, filesystem), `tempo` `1.24.4` (single-binary, OTLP 4317/4318), `alloy` `1.10.0` (DaemonSet collector: pod logs→Loki, OTLP→Tempo). The `sssm-services` chart now auto-renders a ServiceMonitor + injects `OTEL_*` env per service. Remaining: apply; per-service Grafana dashboards; tune alert routes
- [~] QA: unit + Testcontainers; Pact/Spring-Cloud-Contract; Playwright happy-path; k6 smoke (Not be run yet, No real ỉnfrustructor)
- [~] DEPLOY GATE: staging green → prod; rollback runbook verified (Not be run yet, No real ỉnfrustructor)

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

- [ ] `recommendation-service` (Go): candidate gen — in-network + out-of-network (ANN/pgvector) + trend. Refer to X’s post recommendation mechanism from their repository: https://github.com/xai-org/x-algorithm (Ruby base, but we are Go)
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

## Phase 6 - Fontend & Clients

- [ ] Frontends `apps/vue` + `apps/flutter` — generated via official CLIs AFTER the Go/Java backend is complete
