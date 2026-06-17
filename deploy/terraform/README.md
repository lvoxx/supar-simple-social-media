# SSSM — Terraform (Phase 1 IaC)

Root module for the first cloud environment. Composes **official registry
modules** (no hand-rolled networking) per the project rule to reuse community
templates. Cost discipline from [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
drives every choice: NAT *instance* (not Gateway), single-AZ RDS, SPOT nodes,
free S3 gateway endpoint, Cloudflare R2 for zero-egress media.

> **Status:** authored locally; **nothing is applied** (no cloud account yet).
> There is no remote state until you point the backend at a real bucket.

## Pinned versions (verified latest-stable)

| Component | Source | Version |
|-----------|--------|---------|
| AWS provider | `hashicorp/aws` | `~> 6.0` |
| Cloudflare provider | `cloudflare/cloudflare` | `~> 5.0` |
| VPC | `terraform-aws-modules/vpc/aws` | `~> 6.0` |
| EKS | `terraform-aws-modules/eks/aws` | `~> 21.0` |
| RDS | `terraform-aws-modules/rds/aws` | `~> 7.0` |
| NAT instance | `RaJiska/fck-nat/aws` | `~> 1.6` |

## What it creates

- **VPC** (`vpc.tf`): 2-AZ public/private/database subnets; **fck-nat** NAT instance
  for egress (`enable_nat_gateway = false`); **S3 gateway endpoint** (free) plus
  optional ECR/STS/Logs interface endpoints (`enable_interface_endpoints`).
- **EKS** (`eks.tf`): control plane + one SPOT `t3.large` managed node group;
  core add-ons (coredns, kube-proxy, vpc-cni, pod-identity, ebs-csi).
- **RDS** (`rds.tf`): Postgres 17, single-AZ, gp3, encrypted; master password in
  Secrets Manager; reachable only from the EKS node security group.
- **Cloudflare** (`cloudflare.tf`): R2 media bucket; optional proxied app CNAME.

## Usage (when a cloud account exists)

```bash
export CLOUDFLARE_API_TOKEN=...           # R2 + DNS edit
cp terraform.tfvars.example terraform.tfvars   # fill in account/zone/domain

terraform init \
  -backend-config="bucket=sssm-tfstate-<acct>" \
  -backend-config="key=staging/infra.tfstate" \
  -backend-config="region=ap-southeast-1"

terraform plan      # CI also runs tfsec / checkov here (ROADMAP cross-cutting)
terraform apply
terraform output configure_kubectl   # -> aws eks update-kubeconfig ...
```

Local experiments without a backend: `terraform init -backend=false`.

## Notes / follow-ups

- App/DB wiring (DATABASE_URL, REDIS_URL, the Secrets-Manager DB secret) is
  injected into the services via Helm `envFrom` — see `../helm/sssm-services`.
- Redis / Kafka / Keycloak run **in-cluster** at this scale (not managed) and are
  deployed as ArgoCD apps, not Terraform — see `../argocd`.
- `endpoint_public_access = true` is convenient for bootstrap; restrict
  `endpoint_public_access_cidrs` before prod (Phase 5 hardening).
