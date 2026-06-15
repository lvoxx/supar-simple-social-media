# ADR-0003: Authentication at the gateway sidecar, not in services

- Status: Accepted
- Date: 2026-06-15
- Supersedes the "authn in per-service shared middleware" note in earlier architecture drafts.

## Context

Every service behind the edge needs to know who the caller is. The naive approach makes each
service an OAuth2 resource server that fetches the Keycloak JWKS and validates the access token
itself. At our scale (10k–100k users, many services) that means every service round-trips to
Keycloak for keys, duplicates token-validation config, and must be re-touched whenever auth rules
or key rotation change.

## Decision

Token validation is performed **once, by a sidecar on the gateway** (e.g. oauth2-proxy / Envoy
`ext_authz` / Keycloak-aware proxy). The sidecar validates the Keycloak access token and forwards
the authenticated identity to downstream services as **trusted headers**:

| Header | Meaning |
|---|---|
| `X-Auth-Subject` | Keycloak user id (the JWT `sub`, a UUID); equals `profiles.keycloak_id` |
| `X-Auth-Username` | Keycloak `preferred_username` (optional) |
| `X-Auth-Roles` | comma-separated realm roles (e.g. `user,creator`) |

Services **do not** decode JWTs. They run a lightweight pre-authentication filter that trusts these
headers and builds the security context. This applies to **all services behind the gateway**
(`user-service`, `post-service`, `media-service`, `ad-service`, and the Go services).

In `user-service` this is `GatewayAuthenticationFilter` + `AuthenticatedUser`; there is no
`issuer-uri` and no `spring-boot-starter-...-oauth2-resource-server` dependency.

## Trust boundary

The headers are trusted **only because the network topology guarantees services are reachable
exclusively through the gateway** (Kubernetes NetworkPolicy / service mesh; the gateway is the only
ingress to the service subnet). The gateway must **strip any client-supplied `X-Auth-*` headers**
before injecting its own, so a client cannot spoof identity. A missing/invalid `X-Auth-Subject` on
a protected route results in `401`.

## Related security responsibilities (owned at the edge, not in services)

- **Brute-force protection** — Keycloak built-in brute-force detection on login + gateway rate
  limiting on the auth endpoints.
- **Anti-spam / abuse** — gateway/WAF rate limiting (Cloudflare) plus per-user write quotas
  (Redis token bucket) for expensive mutations (follow, post).
- **Caching** — read/response caching at Cloudflare/CDN and gateway; hot profile reads cached in
  Redis. Services stay cache-aware but do not own edge caching.

## Consequences

- One place to rotate keys, enforce auth, and audit; services stay thin and cheap.
- Services are testable without a token: integration tests just set `X-Auth-*` headers.
- Hard dependency on network isolation — if a service is ever exposed directly, header trust must
  be revisited (mTLS or signed identity headers).
