# deploy/

Infrastructure & delivery for SSSM. Database schema is **infra-owned** (apps run
`ddl-auto=validate` and never migrate — see the DB rule in the ROADMAP).

| Dir | What | How it ships |
|-----|------|--------------|
| `migrations/` | Flyway SQL per service (source of truth for schema) | `make migrate` locally; a k8s Job in cloud |
| `terraform/`  | VPC, EKS, RDS, R2, Cloudflare (registry modules) | `terraform apply` (CI: plan + tfsec/checkov) |
| `helm/sssm-services/` | Umbrella chart for the 4 app services | rendered by ArgoCD |
| `argocd/` | App-of-apps: ingress-nginx + sssm-services | `kubectl apply` root-app once |

## Order of operations (first cloud env)

1. `terraform apply` → VPC + EKS + RDS + R2 (see `terraform/README.md`).
2. `aws eks update-kubeconfig ...` (printed by `terraform output configure_kubectl`).
3. `helm install argocd ...` then apply the root app-of-apps (`argocd/README.md`).
4. ArgoCD reconciles ingress-nginx, then `sssm-services`.
5. Feed the ingress NLB hostname back to `terraform` (`alb_dns_name`) for the
   Cloudflare proxied CNAME.

## Not here yet (tracked in ROADMAP Phase 1)

- **Observability** (Prometheus + Grafana + Loki + Tempo): next slice — lands as
  ArgoCD apps under `argocd/apps/` (e.g. `kube-prometheus-stack`, `loki`, `tempo`).
- In-cluster **Redis / Kafka (KRaft) / Keycloak**: deployed as ArgoCD apps at this
  scale rather than managed services (cost discipline).
- **Gateway sidecar** authn (ADR-0003): the services Ingress currently routes
  directly by path; the gateway becomes the single backend once built.
