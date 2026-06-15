# ADR-0002: Spring Boot (Java 25) for standard services, Gin (Go 1.26.3) for hot-path services

- Status: Accepted
- Date: 2026-06-15

## Context

The platform has two workload profiles: (1) transactional, relational, business-logic-heavy
services (accounts, posts, ads, money, server-side admin), and (2) high-fan-out, low-latency,
streaming services (home timeline, recommendations, notifications, DMs, counters).

## Decision

- **Spring Boot, Java 25** for standard / transactional / server-rendered services:
  `user`, `post`, `media`, `ad`, `admin-console`, `ad-console`, `creator-dashboard`.
  Rationale: mature transaction + JPA + security ecosystem, Spring MVC for server-side admin
  pages, strong validation/mapping for CRUD and money.
- **Gin, Go 1.26.3** for high-load / low-latency / streaming services:
  `timeline`, `recommendation`, `notification`, `messaging`, `engagement`, `search`.
  Rationale: goroutine concurrency and small memory footprint give a lower cost-per-request at
  scale, which directly serves the budget; ideal for WebSocket/SSE fan-out and feed assembly.

## Consequences

- Two toolchains. Mitigated by shared **Protobuf schemas** (single source of truth, codegen for
  both languages) and parallel reuse libraries: Java starters (`libs/java-starters`) and Go kit
  (`libs/gokit`).
- Cross-service communication is event-driven (Kafka) + REST/gRPC; contracts are enforced by the
  shared schemas and contract tests.
- Team needs competence in both stacks; CI has per-language pipelines.

## Alternatives considered

- **All Java**: simpler hiring/tooling, but higher memory/cost on the hot path.
- **All Go**: cheaper hot path, but weaker transactional/admin ecosystem for CRUD and money.
