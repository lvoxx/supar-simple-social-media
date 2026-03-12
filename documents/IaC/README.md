# Infrastructure — SSSM Social Platform

> AWS-hosted Kubernetes cluster provisioned and managed entirely as code.  
> Terraform · Ansible · Helm · ArgoCD · AWS EKS

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Service Registry](#service-registry)
- [Tool Responsibilities](#tool-responsibilities)
- [Directory Layout](#directory-layout)
- [AWS Account Setup](#aws-account-setup)
- [Provisioning Guide](#provisioning-guide)
- [Helm Charts](#helm-charts)
- [ArgoCD GitOps](#argocd-gitops)
- [Day-2 Operations](#day-2-operations)

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  AWS Account                                                                  │
│                                                                               │
│  ┌───────────────────────────────────────────────────────────────────────┐   │
│  │  VPC  10.0.0.0/16                                                     │   │
│  │                                                                        │   │
│  │  Public Subnets (3 AZs)      ┌──────────────────────────────────┐    │   │
│  │  10.0.0.0/20  (us-east-1a)   │  ALB (HTTPS :443)                │    │   │
│  │  10.0.16.0/20 (us-east-1b)   │  WAF  +  Shield Std              │    │   │
│  │  10.0.32.0/20 (us-east-1c)   └───────────────┬──────────────────┘    │   │
│  │                                               │                        │   │
│  │  Private Subnets (3 AZs)                     ▼                        │   │
│  │  10.0.48.0/20  (us-east-1a)  ┌───────────────────────────────────┐   │   │
│  │  10.0.64.0/20  (us-east-1b)  │  EKS Cluster (sssm-prod)          │   │   │
│  │  10.0.80.0/20  (us-east-1c)  │                                   │   │   │
│  │                               │  Node Groups:                     │   │   │
│  │                               │  • system   (2–4)   t3.medium     │   │   │
│  │                               │  • app      (4–20)  t3.xlarge     │   │   │
│  │                               │  • infra    (3–10)  r6i.xlarge    │   │   │
│  │                               │  • ai-cpu   (2–8)   c6i.2xlarge   │   │   │
│  │                               │  • ai-gpu   (0–4)   g4dn.xlarge   │   │   │
│  │                               └───────────────┬───────────────────┘   │   │
│  │                                               │                        │   │
│  │  Data Subnet (isolated)                       │                        │   │
│  │  10.0.96.0/20  (us-east-1a)      ┌────────────▼────────────────────┐  │   │
│  │  10.0.112.0/20 (us-east-1b)      │  RDS PostgreSQL  (Multi-AZ HA)  │  │   │
│  │  10.0.128.0/20 (us-east-1c)      │  ElastiCache Redis (cluster)     │  │   │
│  │                                   │  MSK Kafka (3-broker)            │  │   │
│  │                                   │  OpenSearch (ES) cluster         │  │   │
│  │                                   └─────────────────────────────────┘  │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
│  S3: terraform state · MLflow artifacts (via MinIO in-cluster) · backups     │
│  ECR: container image registry                                                │
│  Route 53: DNS                                                                │
│  ACM: TLS certificates                                                        │
│  Secrets Manager: all credentials                                             │
│  CloudWatch: logs + metrics (supplemented by in-cluster Prometheus/Grafana)  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### In-cluster service communication diagram

```
                         ┌─────────────────────────────────────────────────────────┐
                         │  Namespace: sssm                                         │
  Client                 │                                                           │
    │                    │  Spring Boot Services (app node group)                    │
    ▼                    │  ┌──────────────┐   ┌───────────────────────────────┐    │
  ALB/NGINX              │  │ user-service │   │ post-service                  │    │
    │ REST                │  │   :8081      │   │   :8083                       │    │
    ▼                    │  └──────────────┘   │  gRPC ──► post-interaction    │    │
  Kong / API Gateway     │  ┌──────────────┐   │  gRPC ──► post-recommendation │    │
    │                    │  │ media-service│   └───────────────────────────────┘    │
    ├─── REST ───────────►  │   :8082      │                                         │
    │                    │  └──────────────┘   ┌──────────────────────────────┐     │
    ├── post-service ────►  ┌──────────────┐   │ comment-service  :8084        │     │
    ├── comment-service ─►  │group-service │   │  gRPC ──► comment-recommendation│   │
    ├── bookmark-service─►  │   :8089      │   └──────────────────────────────┘     │
    └── ...              │  └──────────────┘                                         │
                         │                                                            │
                         │  ┌──────────────────┐  ┌──────────────┐                  │
                         │  │post-interaction  │  │bookmark-svc  │                  │
                         │  │   :8087          │  │   :8088      │                  │
                         │  └──────────────────┘  └──────────────┘                  │
                         │                                                            │
                         │  Python / AI Services (ai-cpu + ai-gpu node groups)       │
                         │  ┌─────────────────┐  ┌──────────────────────────────┐   │
                         │  │post-guard :8090 │  │post-recommendation :8094     │   │
                         │  │media-guard:8091 │  │comment-recommendation :8095  │   │
                         │  │user-analysis    │  └──────────────────────────────┘   │
                         │  │   :8092         │                                      │
                         │  │ai-dashboard     │  Shared ML Infrastructure:           │
                         │  │   :8093         │  ┌──────────┐ ┌─────┐ ┌──────────┐ │
                         │  └─────────────────┘  │ Qdrant   │ │MinIO│ │ MLflow   │ │
                         │                        │ :6333/34 │ │:9000│ │ :5000    │ │
                         │                        └──────────┘ └─────┘ └──────────┘ │
                         │                                                            │
                         │  Messaging & Caching (infra node group)                   │
                         │  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐  │
                         │  │ Cassandra    │  │  MSK Kafka    │  │   Redis      │  │
                         │  │ (3-node)     │  │  (managed)    │  │ (ElastiCache)│  │
                         │  └──────────────┘  └───────────────┘  └──────────────┘  │
                         └────────────────────────────────────────────────────────────┘
```

---

## Service Registry

| Service                        | Port | Type             | DB                  | New/Updated                             |
| ------------------------------ | ---- | ---------------- | ------------------- | --------------------------------------- |
| user-service                   | 8081 | Spring Boot      | PostgreSQL          | —                                       |
| media-service                  | 8082 | Spring Boot      | PostgreSQL          | —                                       |
| post-service                   | 8083 | Spring Boot      | PostgreSQL          | ✏️ Updated (removed interaction)        |
| comment-service                | 8084 | Spring Boot      | Cassandra           | ✏️ Updated (view count, recommendation) |
| notification-service           | 8085 | Spring Boot      | Cassandra           | —                                       |
| search-service                 | 8086 | Spring Boot      | Elasticsearch       | —                                       |
| post-interaction-service       | 8087 | Spring Boot      | Cassandra           | 🆕 New                                  |
| bookmark-service               | 8088 | Spring Boot      | PostgreSQL          | 🆕 New                                  |
| group-service                  | 8089 | Spring Boot      | PostgreSQL          | —                                       |
| post-guard-service             | 8090 | FastAPI (Python) | PostgreSQL + Qdrant | —                                       |
| media-guard-service            | 8091 | FastAPI (Python) | PostgreSQL          | —                                       |
| user-analysis-service          | 8092 | FastAPI (Python) | PostgreSQL + Qdrant | —                                       |
| ai-dashboard-service           | 8093 | FastAPI (Python) | PostgreSQL          | —                                       |
| post-recommendation-service    | 8094 | FastAPI (Python) | PostgreSQL + Qdrant | 🆕 New                                  |
| comment-recommendation-service | 8095 | FastAPI (Python) | PostgreSQL + Qdrant | 🆕 New                                  |

**Total: 15 services** (was 13)

### gRPC port convention

| Service                        | gRPC port |
| ------------------------------ | --------- |
| user-service                   | 9091      |
| post-service                   | 9093      |
| post-interaction-service       | 9097      |
| bookmark-service               | 9098      |
| post-recommendation-service    | 9094      |
| comment-recommendation-service | 9095      |

---

## Tool Responsibilities

| Tool          | Scope                                                                                        | When used                                              |
| ------------- | -------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| **Terraform** | AWS infrastructure (VPC, EKS, RDS, MSK, ElastiCache, S3, IAM, ACM, Route 53)                 | Initial provision + infrastructure changes             |
| **Ansible**   | Node configuration, baseline security hardening, system packages, EKS worker bootstrap       | After Terraform creates EC2 nodes; on OS-level changes |
| **Helm**      | Kubernetes workloads — all 15 services, operators, monitoring stack, ingress, cert-manager   | Deploy + upgrade application workloads                 |
| **ArgoCD**    | GitOps continuous delivery — watches this repo, auto-syncs Helm releases on image tag change | Ongoing CD after initial cluster setup                 |

### Interaction flow

```
Developer pushes code
  → Jenkins builds image → pushes to ECR
  → Jenkins updates values.yaml (image.tag) in this repo
  → ArgoCD detects change → runs helm upgrade
  → Kubernetes rolls out new pods

Infrastructure engineer changes Terraform
  → terraform plan → review → terraform apply
  → If node group changes: ansible-playbook node-hardening.yaml
```

---

## Directory Layout

```
infrastructure/
├── terraform/
│   ├── environments/
│   │   ├── dev/
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── terraform.tfvars
│   │   ├── staging/
│   │   └── prod/
│   └── modules/
│       ├── vpc/
│       ├── eks/               ← node groups: system, app, infra, ai-cpu, ai-gpu
│       ├── rds/
│       ├── elasticache/
│       ├── msk/
│       ├── opensearch/
│       ├── s3/
│       ├── ecr/
│       ├── iam/
│       └── dns/
├── ansible/
│   ├── inventories/
│   │   ├── dev/
│   │   ├── staging/
│   │   └── prod/
│   ├── roles/
│   │   ├── common/
│   │   ├── eks-node/
│   │   ├── security-hardening/
│   │   ├── gpu-driver/        ← NVIDIA driver install cho ai-gpu nodes
│   │   └── monitoring-agent/
│   ├── node-hardening.yaml
│   ├── cluster-bootstrap.yaml
│   └── ansible.cfg
├── helm/
│   ├── charts/
│   │   ├── sssm-services/     ← tất cả 15 services (sub-charts)
│   │   ├── sssm-infra/        ← Cassandra, Qdrant, MLflow, MinIO (self-managed)
│   │   ├── monitoring/        ← Prometheus, Grafana, Loki, Zipkin
│   │   ├── ingress/           ← AWS LBC + NGINX Ingress
│   │   └── platform/          ← cert-manager, external-secrets, cluster-autoscaler
│   └── environments/
│       ├── dev/values-override.yaml
│       ├── staging/values-override.yaml
│       └── prod/values-override.yaml
├── argocd/
│   ├── bootstrap/
│   │   ├── argocd-install.yaml
│   │   └── app-of-apps.yaml
│   └── applications/
│       ├── sssm-services.yaml
│       ├── sssm-infra.yaml
│       ├── monitoring.yaml
│       ├── ingress.yaml
│       └── platform.yaml
├── k8s/
│   └── db-init/               ← K8S Job/InitContainer manifests cho schema migration
│       ├── user-service/
│       ├── post-service/
│       ├── comment-service/
│       ├── post-interaction-service/   ← NEW: cqlsh scripts
│       ├── bookmark-service/           ← NEW: Flyway SQL
│       ├── post-recommendation-service/← NEW: psql + Qdrant init + MLflow init
│       ├── comment-recommendation-service/ ← NEW: psql + Qdrant init
│       ├── post-guard-service/
│       ├── media-guard-service/
│       ├── user-analysis-service/
│       └── ai-dashboard-service/
└── scripts/
    ├── bootstrap.sh
    ├── init-db-local.sh
    └── rotate-secrets.sh
```

---

## EKS Node Groups (updated)

| Node Group | Instance    | Min/Max | Workloads                                                                                                                           |
| ---------- | ----------- | ------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `system`   | t3.medium   | 2/4     | kube-system, ArgoCD, cert-manager                                                                                                   |
| `app`      | t3.xlarge   | 4/20    | Tất cả Spring Boot services                                                                                                         |
| `infra`    | r6i.xlarge  | 3/10    | Cassandra, Kafka consumers, Redis operators, OpenSearch                                                                             |
| `ai-cpu`   | c6i.2xlarge | 2/8     | post-guard, media-guard, user-analysis, ai-dashboard, **post-recommendation**, **comment-recommendation** (inference không cần GPU) |
| `ai-gpu`   | g4dn.xlarge | 0/4     | Training jobs (scale-to-zero khi không train)                                                                                       |

> **post-recommendation** và **comment-recommendation** dùng LightGBM + sentence-transformers CPU inference (< 100 ms) → không cần GPU node khi serve. GPU chỉ cần khi fine-tune model (APScheduler jobs ban đêm).

---

## AWS Account Setup

### Prerequisites

```bash
brew install terraform ansible helm kubectl awscli argocd

aws configure --profile sssm-prod
# region: us-east-1

# Terraform state backend (one time, manual)
aws s3 mb s3://sssm-terraform-state --region us-east-1
aws s3api put-bucket-versioning \
  --bucket sssm-terraform-state \
  --versioning-configuration Status=Enabled

aws dynamodb create-table \
  --table-name sssm-terraform-locks \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

---

## Provisioning Guide

### Step 1 — Terraform: provision AWS infrastructure

```bash
cd infrastructure/terraform/environments/prod
terraform init
terraform plan -var-file=terraform.tfvars -out=tfplan
terraform apply tfplan
terraform output -json > /tmp/tf-outputs.json
```

Expected apply time: ~30 min cho full prod (thêm ~5 min so với trước do ai-cpu node group).

### Step 2 — Ansible: harden nodes

```bash
aws eks update-kubeconfig \
  --region us-east-1 \
  --name sssm-prod \
  --profile sssm-prod

python3 infrastructure/ansible/inventories/prod/ec2_inventory.py \
  --tf-outputs /tmp/tf-outputs.json

# Harden tất cả nodes
ansible-playbook \
  -i infrastructure/ansible/inventories/prod/ \
  infrastructure/ansible/node-hardening.yaml \
  --extra-vars "env=prod"

# Cài NVIDIA driver trên ai-gpu nodes
ansible-playbook \
  -i infrastructure/ansible/inventories/prod/ \
  infrastructure/ansible/roles/gpu-driver/install.yaml \
  --limit "tag_nodegroup_ai-gpu"
```

### Step 3 — Helm + ArgoCD: deploy workloads

```bash
kubectl create namespace argocd
helm repo add argo https://argoproj.github.io/argo-helm
helm install argocd argo/argo-cd \
  --namespace argocd \
  --values infrastructure/helm/charts/platform/argocd-values.yaml \
  --wait

# Bootstrap App-of-Apps
kubectl apply -f infrastructure/argocd/bootstrap/app-of-apps.yaml

# ArgoCD tự sync theo thứ tự:
#   1. platform (cert-manager, external-secrets, cluster-autoscaler)
#   2. ingress  (ALB controller + NGINX)
#   3. monitoring (Prometheus, Grafana, Loki, Zipkin)
#   4. sssm-infra (Kafka, Redis, Cassandra, Qdrant, MLflow, MinIO)
#   5. sssm-services (tất cả 15 microservices)
```

### Step 4 — Run DB init jobs

```bash
kubectl apply -f infrastructure/k8s/db-init/
kubectl wait --for=condition=complete job --all -n sssm --timeout=600s
```

DB-init jobs chạy theo thứ tự (ArgoCD syncwave annotations):

| Wave | Jobs                                                                        |
| ---- | --------------------------------------------------------------------------- |
| 0    | user-service, media-service (Flyway SQL)                                    |
| 1    | post-service, group-service, bookmark-service (Flyway SQL)                  |
| 2    | comment-service, post-interaction-service, notification-service (cqlsh)     |
| 3    | post-guard-service, user-analysis-service (psql + Qdrant + MLflow)          |
| 4    | post-recommendation-service, comment-recommendation-service (psql + Qdrant) |
| 5    | ai-dashboard-service (psql + Redis Stack modules check)                     |
| 6    | search-service (ES index mapping)                                           |

---

## Helm Charts

See full documentation in `helm/HELM.md`.

### Updated `Chart.yaml` dependencies (parent chart — sssm-services)

```yaml
apiVersion: v2
name: sssm-services
description: SSSM Platform — all 15 application microservices
type: application
version: 1.2.0

dependencies:
  - name: user-service
    version: "1.0.0"
    repository: "file://charts/user-service"
    condition: userService.enabled

  - name: post-service
    version: "1.1.0" # bumped — removed interaction
    repository: "file://charts/post-service"
    condition: postService.enabled

  - name: comment-service
    version: "1.1.0" # bumped — added view count + recommendation
    repository: "file://charts/comment-service"
    condition: commentService.enabled

  - name: post-interaction-service
    version: "1.0.0" # NEW
    repository: "file://charts/post-interaction-service"
    condition: postInteractionService.enabled

  - name: bookmark-service
    version: "1.0.0" # NEW
    repository: "file://charts/bookmark-service"
    condition: bookmarkService.enabled

  - name: post-recommendation-service
    version: "1.0.0" # NEW
    repository: "file://charts/post-recommendation-service"
    condition: postRecommendationService.enabled

  - name: comment-recommendation-service
    version: "1.0.0" # NEW
    repository: "file://charts/comment-recommendation-service"
    condition: commentRecommendationService.enabled

  # ... other existing services unchanged
```

### Node affinity cho AI services

Tất cả AI services (8090–8095) dùng nodeSelector `nodegroup: ai-cpu`:

```yaml
# helm/charts/post-recommendation-service/values.yaml
nodeSelector:
  nodegroup: ai-cpu

tolerations:
  - key: "ai-workload"
    operator: "Equal"
    value: "true"
    effect: "NoSchedule"

resources:
  requests:
    cpu: "1000m"
    memory: "2Gi"
  limits:
    cpu: "4000m"
    memory: "8Gi"

# Training CronJob (dùng ai-gpu node)
trainingJob:
  nodeSelector:
    nodegroup: ai-gpu
  tolerations:
    - key: "nvidia.com/gpu"
      operator: "Exists"
      effect: "NoSchedule"
  resources:
    limits:
      nvidia.com/gpu: 1
```

---

## Kafka Topics Registry (full, updated)

| Topic                           | Producer                         | Consumers                                                                        |
| ------------------------------- | -------------------------------- | -------------------------------------------------------------------------------- |
| `post.created`                  | post-svc                         | notification, search, user-analysis, post-recommendation                         |
| `post.updated`                  | post-svc                         | search                                                                           |
| `post.deleted`                  | post-svc                         | search, comment-svc, post-interaction-svc, bookmark-svc, post-recommendation-svc |
| `post.guard.completed`          | post-guard-svc                   | post-recommendation-svc                                                          |
| `post.interaction.created`      | post-interaction-svc             | notification, user-analysis, post-recommendation                                 |
| `post.interaction.deleted`      | post-interaction-svc             | user-analysis                                                                    |
| `post.view.dwell`               | post-interaction-svc             | post-recommendation-svc                                                          |
| `post.hidden`                   | post-svc                         | post-recommendation-svc                                                          |
| `post.unhidden`                 | post-svc                         | post-recommendation-svc                                                          |
| `post.reported`                 | post-svc                         | ai-dashboard                                                                     |
| `post.bookmarked`               | bookmark-svc                     | post-interaction-svc                                                             |
| `post.unbookmarked`             | bookmark-svc                     | post-interaction-svc                                                             |
| `post.recommendation.scored`    | post-recommendation-svc          | ai-dashboard                                                                     |
| `comment.created`               | comment-svc                      | notification, search, user-analysis, comment-recommendation                      |
| `comment.reacted`               | comment-svc                      | notification, user-analysis, comment-recommendation                              |
| `comment.view.dwell`            | comment-svc                      | comment-recommendation-svc                                                       |
| `comment.deleted`               | comment-svc                      | search                                                                           |
| `comment.reported`              | comment-svc                      | ai-dashboard                                                                     |
| `comment.spam.detected`         | comment-recommendation-svc       | comment-svc, ai-dashboard                                                        |
| `comment.recommendation.scored` | comment-recommendation-svc       | ai-dashboard                                                                     |
| `media.upload.completed`        | media-svc                        | post-svc, notification                                                           |
| `media.upload.failed`           | media-svc                        | post-svc                                                                         |
| `user.followed`                 | user-svc                         | notification                                                                     |
| `user.deleted`                  | user-svc                         | comment-svc, bookmark-svc                                                        |
| `user.avatar.changed`           | user-svc                         | post-svc                                                                         |
| `user.background.changed`       | user-svc                         | post-svc                                                                         |
| `user.profile.updated`          | user-svc                         | search                                                                           |
| `user.verified`                 | user-svc                         | search, notification                                                             |
| `user.preferences.updated`      | user-svc                         | notification                                                                     |
| `user.interaction.updated`      | user-analysis-svc                | post-recommendation-svc                                                          |
| `ai.user.violation.suspected`   | user-analysis-svc                | ai-dashboard, post-recommendation, comment-recommendation                        |
| `ai.model.updated`              | post-guard-svc                   | ai-dashboard, post-recommendation                                                |
| `ai.flagged`                    | post-guard-svc / media-guard-svc | ai-dashboard                                                                     |
| `notification.read`             | notification-svc                 | notification-svc (multi-device)                                                  |
| `group.created`                 | group-svc                        | search                                                                           |
| `group.updated`                 | group-svc                        | search                                                                           |
| `group.deleted`                 | group-svc                        | search                                                                           |
| `group.member.joined`           | group-svc                        | notification                                                                     |
| `group.post.pinned`             | group-svc                        | notification                                                                     |
| `group.post.created`            | group-svc                        | search                                                                           |

---

## ArgoCD GitOps

See `argocd/ARGOCD.md`.

---

## Day-2 Operations

| Task                                 | Command                                                                                         |
| ------------------------------------ | ----------------------------------------------------------------------------------------------- |
| Scale a node group                   | Edit `terraform/modules/eks/variables.tf`, `terraform apply`                                    |
| Deploy new service version           | Update `image.tag` in `helm/environments/prod/values-override.yaml`, commit — ArgoCD auto-syncs |
| Rotate a secret                      | `./scripts/rotate-secrets.sh <secret-name>` → restarts affected pods                            |
| Rolling restart a service            | `kubectl rollout restart deployment/post-recommendation-service -n sssm`                        |
| Access ArgoCD UI                     | `kubectl port-forward svc/argocd-server -n argocd 8080:443`                                     |
| Access Grafana                       | `kubectl port-forward svc/grafana -n monitoring 3000:80`                                        |
| Trigger recommendation model retrain | `POST /api/v1/recommendation/model/refresh` (admin token)                                       |
| Scale ai-cpu for high traffic        | Edit `eks/variables.tf` ai-cpu `max_size`, `terraform apply`                                    |
| Emergency rollback                   | `helm rollback <release> <revision> -n sssm`                                                    |
| Upgrade Helm chart                   | Commit values change → ArgoCD (hoặc `helm upgrade` cho dev)                                     |
