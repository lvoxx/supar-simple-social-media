# deploy/

Infrastructure & delivery for SSSM. Database schema is **infra-owned** (apps run
`ddl-auto=validate` and never migrate — see the DB rule in the ROADMAP).

| Dir | What | How it ships |
|-----|------|--------------|
| `migrations/` | Flyway SQL per service (source of truth for schema) | `make migrate` locally; a k8s Job in cloud |
| `terraform/`  | VPC, EKS, RDS, R2, Cloudflare (registry modules) | `terraform apply` (CI: plan + tfsec/checkov) |
| `helm/sssm-services/` | Umbrella chart for the app services | rendered by ArgoCD |
| `argocd/` | App-of-apps: ingress, services, observability | `kubectl apply` root-app once |

## Order of operations (first cloud env)

1. `terraform apply` → VPC + EKS + RDS + R2 (see `terraform/README.md`).
2. `aws eks update-kubeconfig ...` (printed by `terraform output configure_kubectl`).
3. `helm install argocd ...` then apply the root app-of-apps (`argocd/README.md`).
4. ArgoCD reconciles ingress-nginx, the observability stack, then `sssm-services`.
5. Feed the ingress NLB hostname back to `terraform` (`alb_dns_name`) for the
   Cloudflare proxied CNAME.

## Observability

ArgoCD apps under `argocd/apps/` (pinned upstream charts):
`kube-prometheus-stack` (Prometheus + Grafana + Alertmanager + default
dashboards/alerts, Grafana pre-wired with Loki & Tempo datasources), `loki`
(logs), `tempo` (traces), and `alloy` (DaemonSet collector: pod logs → Loki, an
OTLP endpoint → Tempo). The `sssm-services` chart auto-emits a **ServiceMonitor**
per service with a `metricsPath` and injects `OTEL_*` env pointing at Alloy, so
services are scraped and traced with no per-service wiring.

## Adding a service (do this in the SAME PR that adds the service)

Keeps app and infra in lock-step — the step later phases must not skip:

1. **Helm** — add an entry under `services:` in `helm/sssm-services/values.yaml`:
   `port`, ingress `paths`, probe paths (Java: actuator groups; Go: `/healthz`),
   and `metricsPath` (Java: `/actuator/prometheus`; omit until a Go svc exposes
   metrics). This alone yields Deployment + Service + Ingress + ServiceMonitor +
   tracing env.
2. **GitOps** — nothing extra for app services: the existing `sssm-services`
   ArgoCD app renders the whole map. (Only infra add-ons get their own Argo app.)
3. **Schema** — if the service owns tables, add `migrations/<service>/V1__*.sql`
   and a Flyway runner (`docker/docker-compose.flyway.yml` + `make migrate`; a
   k8s Job in cloud). Apps never migrate.
4. **CI** — ensure the pipeline builds → tests → scans (Trivy) → pushes
   `ghcr.io/.../<service>` and that the image name matches the Helm entry.
5. **Secrets/config** — wire `envFrom` (DB_URL, REDIS_URL, the Secrets-Manager DB
   secret) for the service in values.

## Not here yet (tracked in ROADMAP Phase 1)

- In-cluster **Redis / Kafka (KRaft) / Keycloak**: deployed as ArgoCD apps at this
  scale rather than managed services (cost discipline).
- **Gateway sidecar** authn (ADR-0003): the services Ingress currently routes
  directly by path; the gateway becomes the single backend once built.
