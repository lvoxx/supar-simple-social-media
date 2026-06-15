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
  java/            Spring Boot services (user, post, media, ad, admin-console, ad-console, creator-dashboard)
  go/              Gin services (timeline, recommendation, notification, messaging, engagement, search)
libs/
  java-starters/   Reusable Spring Boot starters (web, security, data, kafka, observability)
  gokit/           Reusable Go packages (httpx, config, observability, kafkax, redisx, pgx)
schemas/           Protobuf event/DTO schemas — single source of truth, codegen for Java + Go
deploy/
  terraform/       AWS infrastructure as code
  helm/            Kubernetes workload charts
web/               Next.js web client
docker/            Local-dev infra (Postgres, Redis, Kafka, Cassandra, OpenSearch, Keycloak)
docs/              Architecture & ADRs
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
- Phased build plan is tracked in the Claude Code project memory (`sssm-build-checklist`).

## License

See [LICENSE](LICENSE).
