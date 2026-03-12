# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

All Spring Boot services are Maven multi-module projects rooted at `spring-services/`.

```bash
# Build all modules (skip tests)
cd spring-services && mvn clean package -DskipTests

# Build a specific service
cd spring-services && mvn clean package -DskipTests -pl service/user-service -am

# Run a specific service
cd spring-services/service/user-service && mvn spring-boot:run

# Unit tests (all / single service / single class)
cd spring-services && mvn test
cd spring-services && mvn test -pl service/user-service
cd spring-services && mvn test -pl service/user-service -Dtest=UserServiceTest

# Integration tests (requires Docker)
cd spring-services && mvn failsafe:integration-test -pl service/user-service
```

**Python FastAPI services:**
```bash
cd python-services/<service-name>
pip install -r requirements.txt
uvicorn app.main:app --reload --port 809x

# Tests
pytest tests/ -v --cov=app
```

**Start infrastructure (Docker Compose):**
```bash
cd docker
docker compose --env-file .dev.env -f docker-compose.postgres.yml up -d
docker compose --env-file .dev.env -f docker-compose.kafka.yml up -d
docker compose --env-file .dev.env -f docker-compose.redis.yml up -d
docker compose --env-file .dev.env -f docker-compose.keycloak.yml up -d

# Then apply DB schemas
./scripts/init-db-local.sh
```

## Architecture Overview

Reactive microservices platform built on **Spring WebFlux + Java 25** (Spring Boot services) and **FastAPI + Python 3.12** (AI/ML services).

### All Services

| Service | Port | DB | Notes |
|---------|------|----|-------|
| user-service | 8081 | PostgreSQL | Profiles, followers, Keycloak sync |
| media-service | 8082 | PostgreSQL | Upload pipeline, AWS S3 + CloudFront CDN |
| post-service | 8083 | PostgreSQL | Posts, reposts, auto-posts; home/explore feed |
| comment-service | 8084 | Cassandra | Nested comments (max depth 3), reactions |
| notification-service | 8085 | Cassandra | Real-time push, multi-device sync |
| search-service | 8086 | Elasticsearch | Full-text, trending, autocomplete |
| group-service | 8087 | PostgreSQL | Groups, membership, roles (PUBLIC/PRIVATE/INVITE_ONLY) |
| private-message-service | 8088 | Cassandra | DM & group chat, typing indicator, reactions |
| message-notification-service | 8089 | Cassandra | FCM, APNs, Web Push; 5s batch window |
| post-interaction-service | — | Cassandra | Likes, shares, view-count buffering |
| bookmark-service | — | PostgreSQL | Bookmark collections, preview denormalisation |
| post-guard-service (FastAPI) | 8090 | PostgreSQL + Qdrant | Content moderation — BERT + RAG |
| media-guard-service (FastAPI) | 8091 | — | NSFW, deepfake, malware detection |
| user-analysis-service (FastAPI) | 8092 | PostgreSQL + Qdrant | Behavior analysis, bot detection (ALS) |
| ai-dashboard-service (FastAPI) | 8093 | PostgreSQL | Admin moderation queue, live WS metrics |
| post-recommendation-service (FastAPI) | 8094 | PostgreSQL + Qdrant | LightGBM LTR post ranking, A/B testing |
| comment-recommendation-service (FastAPI) | 8095 | PostgreSQL + Qdrant | Comment quality ranking, spam detection |

### Module Layout

```
spring-services/
├── common/
│   ├── common-core/     # ApiResponse, exceptions, ULID, security context, base entities, enums
│   └── common-keys/     # Kafka topic names, RouterPaths, ServiceNames constants
├── service/             # Spring Boot business microservices (see table above)
└── starter/             # Custom Spring Boot auto-configuration starters
    ├── postgres-starter     # R2DBC pool, ReactiveAuditorAware (reads userId from Reactor Context)
    ├── cassandra-starter    # Reactive Cassandra session
    ├── redis-starter        # Lettuce, Redisson, Bucket4j rate limiting
    ├── kafka-starter        # Avro + Confluent Schema Registry, reactive producer/consumer, DLT
    ├── elasticsearch-starter
    ├── security-starter     # ClaimExtractionWebFilter, @CurrentUser, UserPrincipalFilter
    ├── metrics-starter      # Micrometer, Zipkin, MdcPropagationWebFilter (traceId/spanId/userId in MDC)
    ├── websocket-starter    # Requires @Bean (cannot be YAML-configured)
    ├── tika-starter
    └── grpc-starter         # UserContextPropagationInterceptor, TracingServerInterceptor,
                             # MetricsServerInterceptor, GlobalGrpcExceptionHandler

python-services/             # FastAPI AI/ML services (ports 8090–8095)
infrastructure/              # K8s Helm charts, Jenkins, ArgoCD, DB init scripts
docker/                      # Compose files + .dev.env
documents/                   # Per-service detailed documentation
```

### Request Flow

```
K8S Ingress → JWT validation (gateway) → inject X-User-Id, X-User-Roles, X-Forwarded-For
  → RouterFunction → HandlerFunction → Service (interface+Impl) → Repository (R2DBC/Reactive)
                                                                 → Kafka publisher
```

`UserPrincipalFilter` (security-starter) reads forwarded headers and puts `UserPrincipal` into the Reactor Context. Use `@CurrentUser` to inject it into handler methods. **No JWT validation inside services.**

### Layered Package Structure (per Spring Boot service)

```
io.github.lvoxx.<service>/
├── config/              # @Configuration only when YAML is insufficient
├── domain/entity/       # R2DBC/Cassandra entities extending SoftDeletableEntity
├── domain/repository/   # ReactiveCrudRepository interfaces
├── domain/event/        # Avro-generated Kafka event POJOs
├── application/service/ # Business logic — interface + Impl
├── application/dto/     # Java records: <Action>Request / <Entity>Response
├── application/mapper/  # MapStruct @Mapper interfaces
├── infrastructure/kafka/    # Producers, consumers
├── infrastructure/external/ # WebClient adapters (timeout 5s, retry 3× backoff, Resilience4j CB)
└── web/router|handler/  # Functional RouterFunction + HandlerFunction
```

**FastAPI service structure:** `app/api/v1/routes/`, `app/services/`, `app/infrastructure/` (database, redis, kafka, ml/), `app/core/`, `app/config.py` (Pydantic Settings).

### Key Conventions

**IDs:** ULID via `UlidGenerator` from `common-core`. Stored as UUID in PostgreSQL/Cassandra.

**API response envelope:**
```json
{ "success": true, "data": {...}, "error": null, "meta": { "requestId": "...", "timestamp": "..." } }
```
Always use `ApiResponse<T>` from `common-core`. Paginated results use `PageResponse<T>`:
```json
{ "items": [...], "nextCursor": "01HXZ...", "hasMore": true, "total": null }
```
(`total` is `null` for Cassandra — no cheap COUNT.)

**Kafka event envelope** (always wrap payloads — `KafkaEventEnvelope<T>` from kafka-starter):
```java
record KafkaEventEnvelope<T>(String eventId, String eventType, String version,
    Instant timestamp, String producerService, String correlationId, T payload) {}
```

**Naming:**

| Element | Convention |
|---------|-----------|
| Java interface | PascalCase, no `I` prefix (`UserService`) |
| Java impl | `<Interface>Impl` (`UserServiceImpl`) |
| DTO | `<Action>Request` / `<Entity>Response` |
| Kafka topic | `domain.entity.action` (`user.profile.updated`) |
| Redis key | `entity:qualifier:{id}` (`user:profile:{userId}`) |
| DB table/column | `snake_case` plural / `snake_case` |

**Configuration:** Prefer YAML over `@Bean`. Write `@Bean` only for types not provided by auto-configuration, custom init logic, or `@ConditionalOn*`. Config priority: K8S env vars → `application-{profile}.yaml` → `application.yaml` → starter defaults. Profiles: `dev`, `test`, `staging`, `prod`.

**Database rules:**
- One primary DB per service (PostgreSQL **or** Cassandra **or** Elasticsearch). Redis is cache/lock only.
- No FK constraints — referential integrity enforced in application code.
- Soft delete only: `is_deleted`, `deleted_at`, `deleted_by` on all user-generated entities.
- DB schemas applied by K8S Jobs/InitContainers only — never at service startup.
  - PostgreSQL → Flyway CLI (`infrastructure/k8s/db-init/<service>/sql/`)
  - Cassandra → `cqlsh` InitContainer (`/cql/`); set `spring.cassandra.schema-action: NONE`
  - Elasticsearch → `curl` Job (`/mappings/`)
- Cassandra: COUNTER columns for atomic increments; TIMEUUID clustering keys for cursor pagination; TTLs on append-only data (notifications 90d, interactions 180d, message notification log 30d).
- Cassandra: append-only design in hot paths (no UPDATE); partition by e.g. `(post_id, interaction_type)` for viral scale.

**Reactive rules:** No `.block()` in hot paths. No `Thread.sleep()` — use `Mono.delay()`. Use `switchIfEmpty(Mono.error(...))` for null guards. `Schedulers.boundedElastic()` only for blocking I/O. All FastAPI routes are `async def`.

**Caching:** Two-level — L1 Caffeine (1 s TTL, hot set) → L2 Redis (`@Cacheable`, configurable TTL). Mutations: `@CacheEvict`/`@CachePut` on Redis then publish Kafka event. Distributed locks via Redisson `RLock` (e.g. `lock:user:{userId}:follow`, `tryLock(500ms, 5s lease)`).

Counter display uses a **bucket strategy** via `CounterDisplayUtil` (common-core): Redis cache has no TTL; evicted only when the display bucket boundary changes (e.g. 999→1K, 1.2K→1.3K). Avoids constant cache invalidation on hot content.

View counts use a **time-buffered flush**: Redis accumulates counts with 120s rolling TTL per `post:view-buffer:{postId}`; a 60s scheduled job flushes deltas to Cassandra in batch. Eventual consistency is acceptable for view counts.

**Kafka:** Avro + Confluent Schema Registry. `acks=all`, `enable.idempotence=true`. Manual offset commit after processing. DLT per topic (`<topic>.DLT`). Breaking schema changes → new suffix (`post.created.v2`). Publish via `ReactiveKafkaProducerTemplate<String, SpecificRecord>`.

**Inter-Service Communication:**
- **Sync:** Reactive WebClient — `timeout(5s)`, `retryWhen(Retry.backoff(3, 200ms))`, Resilience4j `@CircuitBreaker`
- **Async:** Kafka (preferred for side-effects, fan-out, analytics)
- **gRPC:** `net.devh grpc-spring-boot-starter`; all calls use `withDeadlineAfter()` (3–5s); callers must handle timeout with fallback (e.g. chronological feed if recommendation times out)
- **Event sourcing:** Axon Framework for behavioral propagation (`UpdateUserPreferencesCommand` → `UserPreferencesUpdatedEvent` fan-out). Not used for data fetch.

**Rate limiting:** Redis Token Bucket via Bucket4j + Redisson. Config in `application.yaml` under `sssm.rate-limit`. Redis key: `sssm:rate-limit:<key>`.

**Logging fields:** Every log entry includes `timestamp`, `level`, `service`, `traceId`, `spanId`, `userId`, `requestId`. Tracing: Zipkin + OpenTelemetry (sampled 10% prod, 100% dev).

### Domain-Specific Rules

- **Comment depth:** MAX_DEPTH = 3. Replying to a depth-2 comment returns 422 `MAX_REPLY_DEPTH_EXCEEDED`.
- **Message edit:** TEXT messages only; 15-minute window; sender only.
- **Message forward:** Creates new `FORWARDED` message (reference, not copy). Shows `[Message unavailable]` if source deleted.
- **Read receipts:** If user has `readReceipts=false`, Cassandra is still updated but WS event is NOT broadcast.
- **Typing indicator:** Redis 5s TTL key; client re-sends every 3s; fan-out via Redis Pub/Sub across pods. Requires `notify-keyspace-events "KEx"` on Redis (set via CONFIG SET at startup).
- **Group pinned posts:** Max 5 per group (enforced in application layer).
- **Group post flow:** group-service validates permission → forwards to post-service with `X-Group-Id` header → post-service creates with `group_id` → publishes `post.created` → group-service consumes and inserts `group_post_associations`.
- **Post status transitions:** DRAFT → PENDING_MEDIA (if media attached) → PUBLISHED (on `media.upload.completed`). Auto-posts (type=AUTO) generated by `user.avatar.changed` / `user.background.changed` Kafka events if user setting `autoPostProfileChanges=true`.
- **Bookmarks:** Preview text (`author_id`, `preview_text`, `media_thumb`, `post_created_at`) is denormalised. On post deletion, preview set to `'[Post đã bị xoá]'`.
- **Feed scoring (explore):** `score = (like×2 + share×3 + comment_count) / (hours_old + 2)`. Home feed is chronological from followed users. Both cached in Redis Sorted Set (`feed:home:{userId}` 30s, `feed:explore:global` 1 min).

### common-core Reference

**Exception hierarchy:**

| Class | HTTP | When to use |
|-------|------|-------------|
| `ResourceNotFoundException` | 404 | Entity not found |
| `ConflictException` | 409 | Duplicate state (already liked) |
| `ForbiddenException` | 403 | Missing ownership/role |
| `ValidationException` | 422 | Invalid input / business rule |
| `ExternalServiceException` | 502 | Upstream service failed |
| `RateLimitExceededException` | 429 | Rate limit exceeded |

**Key shared enums:** `UserRole` (USER/MODERATOR/ADMIN/SYSTEM), `ContentStatus` (ACTIVE/HIDDEN/FLAGGED/PENDING_REVIEW/DELETED), `MediaType`, `NotificationType`, `GroupMemberRole` (OWNER/ADMIN/MODERATOR/MEMBER), `GroupVisibility` (PUBLIC/PRIVATE/INVITE_ONLY), `ConversationType` (DIRECT/GROUP_CHAT/GROUP_CHANNEL), `MessageStatus` (SENT/DELIVERED/READ/FAILED/DELETED).

**Base entities:** `AuditableEntity` (createdAt, updatedAt, createdBy, updatedBy) → `SoftDeletableEntity` (adds isDeleted, deletedAt, deletedBy).

### ML / AI Services

All AI services use a **shared MLflow instance** (MinIO S3-compatible artifact store). Model lifecycle: Development → Staging → Production → Archived. Training schedules: post-guard 02:00 UTC daily, post-recommendation 03:00 UTC daily, user-analysis periodic. media-guard does no training — only daily model stage promotion check.

**Vector embeddings:** `sentence-transformers/all-MiniLM-L6-v2` (384-dim, cosine) used by post-guard RAG, comment-recommendation, post-recommendation. LSTM Autoencoder (256-dim) used by user-analysis. Stored in Qdrant with HNSW index.

**post-guard semantic cache:** RedisVL with cosine distance threshold 0.10 (tight, to avoid over-generalisation).

**user-analysis bot detection:** Publishes `ai.user.violation.suspected` when `bot_score > 0.85`.

### Testing

- **Unit:** JUnit 5 + Mockito. Mock all I/O. Target 80% line coverage.
- **Integration:** Testcontainers (real Docker). `WebTestClient` for Spring endpoints. LocalStack for S3.
- **Python:** `httpx` for FastAPI. `pytest tests/ -v --cov=app`.
- **Test naming:** `methodName_givenCondition_expectedResult`

### Git Conventions

```
feat(post-service): add bookmark endpoint
fix(user-service): correct follower count on unfollow
chore(kafka-starter): upgrade kafka-clients to 3.7
```
Branches: `feature/` · `bugfix/` · `hotfix/` → `develop` → `main`.

## Important Files

| File | Purpose |
|------|---------|
| `CONVENTIONS.md` | Full coding rules and naming standards |
| `ARCHITECTURE.md` | System design, HA topology, DB rationale, data flows |
| `docker/.dev.env` | Local dev env vars (DB creds, S3/LocalStack, etc.) |
| `spring-services/pom.xml` | Root POM — Spring Boot 4.0.2, Java 25, dependency management |
| `spring-services/common/common-core/` | Shared base classes, exceptions, enums used by all services |
| `spring-services/common/common-keys/` | Shared constants (topic names, service names, route paths) |
| `infrastructure/k8s/db-init/` | DB schema init scripts per service |
| `documents/Spring-Services/` | Detailed per-service documentation |
| `documents/AI-Services/` | Detailed AI service documentation |
| `documents/Other-Services/grpc-inter-service.md` | Full gRPC inter-service call registry |
| `documents/IaC/` | Terraform, Helm, ArgoCD, Ansible documentation |
