# Contributing

## Prerequisites

- JDK 25, Maven 3.9+
- Go 1.26.3
- Docker + Docker Compose
- `buf` (for Protobuf codegen)
- Node 20+ (for `web/`)

## Local development

```bash
make up        # start local infra (Postgres, Redis, Kafka, Cassandra, OpenSearch, Keycloak)
make proto     # regenerate Java + Go code from schemas/
make build     # build all Java + Go modules
make test      # run unit + integration tests
make down      # stop local infra
```

## Conventions

- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`). Releases are automated.
- **Branches**: `feat/<scope>`, `fix/<scope>`; PRs target `main`.
- **Reuse first**: new Spring Boot services depend on `libs/java-starters/*`; new Go services
  depend on `libs/gokit`. Do not copy-paste cross-cutting infrastructure code.
- **Schemas**: event/DTO changes go in `schemas/` (Protobuf) and must be backward-compatible
  (enforced by `buf breaking`). Never hand-edit generated code.
- **Every endpoint**: authn (Keycloak/OIDC) + rate limit + structured log + OTel trace +
  Prometheus metric.
- **Quality gate**: unit + Testcontainers integration tests; coverage threshold enforced in CI;
  no critical CVEs (Trivy/CodeQL).

## Decision records

Architectural decisions are captured as ADRs in [docs/adr/](docs/adr/). Add a new numbered file
for any significant decision.
