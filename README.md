# Supar Simple Social Media Platform (SSSM) — Backend

> Production-ready reactive microservice backend.
> Spring Boot 4.0.2 · FastAPI 0.133 · Kafka · gRPC · Keycloak · Kubernetes

---

## Table of Contents

- [Architecture](#architecture)
- [Services](#services)
- [Shared Modules](#shared-modules)
- [Technology Stack](#technology-stack)
- [Getting Started](#getting-started)
- [Database Initialisation](#database-initialisation)
- [CI/CD](#cicd)
- [Documentation Index](#documentation-index)

---

## Architecture

```mermaid
flowchart TD

    Internet[Internet]

    Keycloak[Keycloak<br/>OAuth2 / OIDC<br/>Token issuer · User federation]

    Gateway[K8S Ingress + JWT Validation Gateway<br/>Injects: X-User-Id · X-User-Roles · X-Forwarded-For]

    Internet --> Keycloak
    Keycloak -->|JWT| Gateway

    Gateway --> UserService[user-service :8081<br/>PostgreSQL]
    Gateway --> MediaService[media-service :8082<br/>PostgreSQL]
    Gateway --> PostService[post-service :8083<br/>PostgreSQL]
    Gateway --> CommentService[comment-service :8084<br/>Cassandra]
    Gateway --> NotificationService[notification-service :8085<br/>Cassandra]
    Gateway --> SearchService[search-service :8086<br/>Elasticsearch]
    Gateway --> GroupService[group-service :8087<br/>PostgreSQL]
    Gateway --> PMService[private-message-service :8088<br/>Cassandra]
    Gateway --> MsgNotifService[message-notification-service :8089<br/>Cassandra]
    Gateway --> PostGuard[post-guard-service :8090<br/>PostgreSQL (FastAPI)]
    Gateway --> MediaGuard[media-guard-service :8091<br/>FastAPI]
    Gateway --> UserAnalysis[user-analysis-service :8092<br/>PostgreSQL (FastAPI)]
    Gateway --> AIDashboard[ai-dashboard-service :8093<br/>PostgreSQL (FastAPI)]
    Gateway --> PostRecommend[post-recommendation-service :8094<br/>PostgreSQL (FastAPI)]
    Gateway --> CommentRecommend[comment-recommendation-service :8095<br/>PostgreSQL (FastAPI)]
    Gateway --> PostInteraction[post-interaction-service :8096<br/>Cassandra]
    Gateway --> BookmarkService[bookmark-service :8097<br/>PostgreSQL]

    subgraph Shared_Infrastructure
        Kafka[Apache Kafka]
        Redis[Redis<br/>Cache / Lock]
        Zipkin[Zipkin]
        ELK[ELK Stack]
        ArgoCD[ArgoCD]
    end
```

### Design principles

- **Reactive everywhere** — Spring WebFlux / R2DBC; fully `async/await` FastAPI.
- **One primary database per service** — PostgreSQL _or_ Cassandra _or_ Elasticsearch. Redis is a cache/lock layer only. Services that would naturally mix two engines are split into separate microservices.
- **No foreign-key constraints** — referential integrity is enforced at the application layer.
- **No table relations across services** — cross-service data fetched via Kafka events or WebClient; never via shared DB.
- **DB init via Kubernetes** — all schema migrations and index mappings are applied by K8S Jobs / InitContainers. Services never run migrations.
- **Soft-delete only** — user-generated data is never physically deleted.
- **JWT validated at the gateway** — services read claims from forwarded headers; zero auth logic in individual services.

---

## Services

### Spring Boot Services

| Service                                                                           | Port | Primary DB    | Description                                          |
| --------------------------------------------------------------------------------- | ---- | ------------- | ---------------------------------------------------- |
| [user-service](./Spring-Services/user-service.md)                                 | 8081 | PostgreSQL    | Profiles, followers, account settings, Keycloak sync |
| [media-service](./Spring-Services/media-service.md)                               | 8082 | PostgreSQL    | Upload, processing pipeline, AWS S3 + CloudFront CDN |
| [post-service](./Spring-Services/post-service.md)                                 | 8083 | PostgreSQL    | Posts, likes, reposts, bookmarks, feeds              |
| [comment-service](./Spring-Services/comment-service.md)                           | 8084 | Cassandra     | Nested comments, high-throughput writes              |
| [notification-service](./Spring-Services/notification-service.md)                 | 8085 | Cassandra     | Real-time push, multi-device read sync               |
| [search-service](./Spring-Services/search-service.md)                             | 8086 | Elasticsearch | Full-text search, trending, autocomplete             |
| [group-service](./Spring-Services/group-service.md)                               | 8087 | PostgreSQL    | Groups, membership, roles, join screening            |
| [private-message-service](./Spring-Services/private-message-service.md)           | 8088 | Cassandra     | DM & group chat, reactions, forwarding               |
| [message-notification-service](./Spring-Services/message-notification-service.md) | 8089 | Cassandra     | Push notifications — FCM, APNs, Web Push             |
| [post-interaction-service](./Spring-Services/post-interaction-service.md)         | 8096 | Cassandra     | Likes, shares, bookmark counters, view tracking      |
| [bookmark-service](./Spring-Services/bookmark-service.md)                         | 8097 | PostgreSQL    | Bookmark CRUD, collections, full-text search         |

### FastAPI AI Services

| Service                                                                           | Port | Primary DB | Description                                        |
| --------------------------------------------------------------------------------- | ---- | ---------- | -------------------------------------------------- |
| [post-guard-service](./AI-Services/post-guard-service.md)                         | 8090 | PostgreSQL | Content moderation — BERT + RAG                    |
| [media-guard-service](./AI-Services/media-guard-service.md)                       | 8091 | —          | NSFW, malware, deepfake detection                  |
| [user-analysis-service](./AI-Services/user-analysis-service.md)                   | 8092 | PostgreSQL | Behaviour analysis, bot detection, recommendations |
| [ai-dashboard-service](./AI-Services/ai-dashboard-service.md)                     | 8093 | PostgreSQL | Admin AI dashboard, moderation queue               |
| [post-recommendation-service](./AI-Services/post-recommendation-service.md)       | 8094 | PostgreSQL | ML-powered post ranking, personalised home feed    |
| [comment-recommendation-service](./AI-Services/comment-recommendation-service.md) | 8095 | PostgreSQL | Comment quality ranking, spam detection via Qdrant |

---

## Shared Modules

Located under `spring-services/`:

| Module                                                               | Path                             | Description                                            |
| -------------------------------------------------------------------- | -------------------------------- | ------------------------------------------------------ |
| [common-core](./modules/common-core.md)                              | `common/common-core`             | Exceptions, ApiResponse, enums, ULID, security context |
| [postgres-starter](./modules/starters.md#postgres-starter)           | `starters/postgres-starter`      | R2DBC connection pool, `ReactiveAuditorAware`          |
| [cassandra-starter](./modules/starters.md#cassandra-starter)         | `starters/cassandra-starter`     | Reactive Cassandra session                             |
| [redis-starter](./modules/starters.md#redis-starter)                 | `starters/redis-starter`         | ReactiveRedisTemplate, Redisson, rate limiter          |
| [kafka-starter](./modules/starters.md#kafka-starter)                 | `starters/kafka-starter`         | Reactive Kafka producer/consumer, DLT                  |
| [elasticsearch-starter](./modules/starters.md#elasticsearch-starter) | `starters/elasticsearch-starter` | Reactive ES client                                     |
| [metrics-starter](./modules/starters.md#metrics-starter)             | `starters/metrics-starter`       | Micrometer, Zipkin, MDC filter                         |
| [websocket-starter](./modules/starters.md#websocket-starter)         | `starters/websocket-starter`     | Reactive WebSocket adapter                             |
| [security-starter](./modules/starters.md#security-starter)           | `starters/security-starter`      | JWT claim extraction, `@CurrentUser`                   |

---

## Technology Stack

| Layer                | Technology                                                |
| -------------------- | --------------------------------------------------------- |
| Spring Boot services | Spring Boot 4.0.2, Java 25, Maven multi-module            |
| FastAPI services     | FastAPI 0.133, Python 3.12                                |
| Reactive runtime     | Spring WebFlux, Project Reactor, asyncio                  |
| Auth                 | Keycloak (OAuth2 + OIDC) — validation at K8S Ingress only |
| Event bus            | Apache Kafka (reactor-kafka / aiokafka)                   |
| Inter-service sync   | gRPC (net.devh grpc-spring-boot-starter)                  |
| Relational DB        | PostgreSQL 16 (R2DBC)                                     |
| Wide-column DB       | Apache Cassandra 4 (reactive)                             |
| Search               | Elasticsearch 8 (reactive)                                |
| Cache / lock         | Redis 7 + Redisson                                        |
| Media CDN            | AWS S3 + CloudFront                                       |
| Vector store         | ChromaDB / Qdrant (AI services only)                      |
| Tracing              | Zipkin + OpenTelemetry                                    |
| Metrics              | Micrometer + Prometheus                                   |
| Logging              | Logback JSON + ELK Stack                                  |
| Containers           | Docker (extracted JAR + Python multi-stage)               |
| Orchestration        | Kubernetes + Helm                                         |
| CI                   | Jenkins (declarative pipelines)                           |
| CD                   | ArgoCD (GitOps)                                           |

---

## Getting Started

### Prerequisites

Docker ≥ 4 · Java 25 · Python 3.12 · Maven ≥ 3.9 · Helm ≥ 3.14 · kubectl

### Start infrastructure locally

```bash
# All infrastructure: Kafka, PostgreSQL, Cassandra, Elasticsearch, Redis, Keycloak, Zipkin
docker compose -f docker-compose.infra.yml up -d

# Apply all schemas (runs the same init containers locally)
./scripts/init-db-local.sh
```

### Build & run services

```bash
cd spring-services && mvn clean package -DskipTests
docker compose -f docker-compose.services.yml up
```

### Run tests

```bash
mvn test                                        # unit tests
mvn failsafe:integration-test                   # integration tests (needs Docker)
cd python-services/post-guard-service && pytest tests/ -v --cov=app
```

---

## Database Initialisation

**Services never initialise their own schemas.**  
All DDL / CQL / index mappings are applied by Kubernetes resources before the service Pod starts.

| Engine        | K8S resource    | Tool                            | Scripts location                                 |
| ------------- | --------------- | ------------------------------- | ------------------------------------------------ |
| PostgreSQL    | `Job`           | Flyway CLI (`flyway/flyway:10`) | `infrastructure/k8s/db-init/<service>/sql/`      |
| Cassandra     | `InitContainer` | `cqlsh` (`cassandra:4.1`)       | `infrastructure/k8s/db-init/<service>/cql/`      |
| Elasticsearch | `Job`           | `curl` (`curlimages/curl:8`)    | `infrastructure/k8s/db-init/<service>/mappings/` |

The Spring Boot `application.yaml` for Cassandra services always sets `spring.cassandra.schema-action: NONE`.  
PostgreSQL services do not include any `flyway.*` or `spring.flyway.*` configuration.

---

## CI/CD

### Jenkins (CI) — `infrastructure/jenkins/`

Stages: `Checkout` → `Build & Unit Test` → `Integration Test` → `SonarQube` → `Docker Build & Push` → `Helm Lint` → `Update GitOps Repo`

### ArgoCD (CD) — `infrastructure/argocd/`

Watches `infrastructure/helm/charts/`. Automated sync with pruning and self-healing on image tag commit.

---

## Documentation Index

| Document                                                                                            | Description                              |
| --------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| [ARCHITECTURE.md](./ARCHITECTURE.md)                                                                | Full system design, data flows, HA       |
| [CONVENTIONS.md](./CONVENTIONS.md)                                                                  | Coding rules, naming, Git workflow       |
| [Spring-Service/user-service.md](./Spring-Services/user-service.md)                                 | user-service                             |
| [Spring-Service/media-service.md](./Spring-Services/media-service.md)                               | media-service                            |
| [Spring-Service/post-service.md](./Spring-Services/post-service.md)                                 | post-service                             |
| [Spring-Service/comment-service.md](./Spring-Services/comment-service.md)                           | comment-service                          |
| [Spring-Service/notification-service.md](./Spring-Services/notification-service.md)                 | notification-service                     |
| [Spring-Service/search-service.md](./Spring-Services/search-service.md)                             | search-service                           |
| [Spring-Service/group-service.md](./Spring-Services/group-service.md)                               | group-service                            |
| [Spring-Service/private-message-service.md](./Spring-Services/private-message-service.md)           | private-message-service                  |
| [Spring-Service/message-notification-service.md](./Spring-Services/message-notification-service.md) | message-notification-service             |
| [Spring-Service/post-interaction-service.md](./Spring-Services/post-interaction-service.md)         | post-interaction-service                 |
| [Spring-Service/bookmark-service.md](./Spring-Services/bookmark-service.md)                         | bookmark-service                         |
| [AI-Service/post-guard-service.md](./AI-Services/post-guard-service.md)                             | post-guard-service (FastAPI)             |
| [AI-Service/media-guard-service.md](./AI-Services/media-guard-service.md)                           | media-guard-service (FastAPI)            |
| [AI-Service/user-analysis-service.md](./AI-Services/user-analysis-service.md)                       | user-analysis-service (FastAPI)          |
| [AI-Service/ai-dashboard-service.md](./AI-Services/ai-dashboard-service.md)                         | ai-dashboard-service (FastAPI)           |
| [AI-Service/post-recommendation-service.md](./AI-Services/post-recommendation-service.md)           | post-recommendation-service (FastAPI)    |
| [AI-Service/comment-recommendation-service.md](./AI-Services/comment-recommendation-service.md)     | comment-recommendation-service (FastAPI) |
| [modules/common-core.md](./modules/common-core.md)                                                  | common-core shared library               |
| [modules/starters.md](./modules/starters.md)                                                        | All starters reference                   |
