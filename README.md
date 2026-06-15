# supar-simple-social-media (SSSM)

An X (Twitter)-like social platform built as a polyglot microservice system, designed to run
production-grade on a **$1,000–3,000/month** infrastructure budget.

- **Standard / transactional services** (CRUD, money, server-side admin): **Spring Boot, Java 25**
- **High-load / low-latency services** (timeline, recommendations, notifications, messaging): **Gin, Go 1.26.3**
- **Media**: direct-to-storage uploads on **Cloudflare R2** (zero egress) with `imgproxy` + HLS transcode
- **Edge**: Cloudflare (WAF/CDN/cache) → AWS ALB → NGINX Ingress on EKS
- **CI/CD**: GitHub Actions (free for public repos) → GHCR → ArgoCD GitOps

## Repository layout

```
apps/
  java/                  Maven reactor — apps/java/pom.xml manages ALL Java modules in one place
    user-service/          Spring Boot service (generated via Spring Initializr)
    starters/              Shared Spring Boot starters (e.g. sssm-postgres-starter)
    common/                Shared library modules (e.g. sssm-common-core)
  go/                    Single Go module — apps/go/go.mod manages ALL Go deps in one place
    timeline-service/      Gin service (package main)
    common/                Shared Go packages (e.g. common/httpx)
  vue/ , flutter/        Web (Vue) + mobile (Flutter) — added AFTER the backend, via official CLIs
schemas/               Protobuf event/DTO schemas — single source of truth, codegen for Java + Go
deploy/
  terraform/           AWS infrastructure as code
  helm/                Kubernetes workload charts
docker/                Local-dev infra (Postgres, Redis, Kafka, Cassandra, OpenSearch, Keycloak)
docs/                  Architecture, ADRs, ROADMAP (the build checklist)
```

## Quick start (local dev)

```bash
make up        # start local infra (docker compose)
make build     # build Java + Go modules
make proto     # regenerate code from schemas/
make down      # stop local infra
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — system design
- [docs/adr/](docs/adr/) — architecture decision records
- [docs/ROADMAP.md](docs/ROADMAP.md) — phased build checklist (tracked in-repo)

## License

See [LICENSE](LICENSE).
