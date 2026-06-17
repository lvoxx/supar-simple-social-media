# SSSM — GitOps (ArgoCD)

App-of-apps. ArgoCD is the single thing installed imperatively; everything else
(ingress, the SSSM services, and — next slice — monitoring) is a git-tracked
`Application` that ArgoCD reconciles and self-heals.

```
argocd/
  projects/sssm.yaml      AppProject (repo + destination allow-list)
  root-app.yaml           app-of-apps -> watches apps/
  apps/
    ingress-nginx.yaml    upstream chart, pinned 4.15.1
    sssm-services.yaml    in-repo chart deploy/helm/sssm-services
    (monitoring goes here in the Observability slice)
```

## Bootstrap (after `terraform apply` + `update-kubeconfig`)

Install ArgoCD itself from the **upstream chart, pinned** (matches the
"reference upstream, don't hand-roll" rule):

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update

helm install argocd argo/argo-cd \
  --namespace argocd --create-namespace \
  --version 9.5.21        # Argo CD v3.x — verified latest stable

# Hand the cluster to GitOps:
kubectl apply -f deploy/argocd/projects/sssm.yaml
kubectl apply -f deploy/argocd/root-app.yaml
```

From here ArgoCD owns ingress-nginx and the SSSM services. Get the initial admin
password with:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d
```

## Before this reconciles cleanly

- Replace `repoURL` / `targetRevision` if you fork or deploy from a non-`main`
  branch.
- The services chart expects images at `ghcr.io/lvoxx/supar-simple-social-media/<service>`;
  CI must push them first, and the GHCR pull secret must exist in the `sssm`
  namespace (or the packages be public).
- `apps/ingress-nginx.yaml` requests an AWS **NLB**; the resulting hostname feeds
  back into Terraform's `alb_dns_name` to create the Cloudflare CNAME.
