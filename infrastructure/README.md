# SSSM — Infrastructure

> **Terraform · Ansible · Helm · ArgoCD · Jenkins**
> AWS EKS · PostgreSQL · Cassandra · Kafka · Redis · OpenSearch

Tài liệu này là hướng dẫn duy nhất để **provision**, **vận hành**, và **maintain** toàn bộ hạ tầng của SSSM Platform.

---

## Mục lục

1. [Tổng quan kiến trúc](#1-tổng-quan-kiến-trúc)
2. [Cách secrets được quản lý](#2-cách-secrets-được-quản-lý)
3. [Chuẩn bị môi trường](#3-chuẩn-bị-môi-trường)
4. [Provision từ đầu (First-time setup)](#4-provision-từ-đầu-first-time-setup)
5. [CI/CD Flow](#5-cicd-flow)
6. [Thêm secret mới](#6-thêm-secret-mới)
7. [Thêm service mới](#7-thêm-service-mới)
8. [Day-2 Operations](#8-day-2-operations)
9. [Cấu trúc thư mục](#9-cấu-trúc-thư-mục)

---

## 1. Tổng quan kiến trúc

```
Developer  ──push──►  GitHub
                          │
                    Jenkins CI
                    (build, test, push image to ECR)
                          │
                    ArgoCD (GitOps)
                    (helm upgrade khi image.tag thay đổi)
                          │
                    EKS Cluster
                    ┌─────────────────────────────────┐
                    │  Namespace: sssm                │
                    │  13 microservices               │
                    │                                 │
                    │  Namespace: sssm-infra          │
                    │  Cassandra · Qdrant · MinIO     │
                    │                                 │
                    │  Namespace: monitoring          │
                    │  Prometheus · Grafana · Loki    │
                    └─────────────────────────────────┘
                          │
              AWS Managed Services
              RDS Aurora · MSK Kafka · ElastiCache
              OpenSearch · S3 · ECR · Secrets Manager
```

### Phân công công việc

| Tool          | Làm gì                                                                     | Khi nào chạy                       |
| ------------- | -------------------------------------------------------------------------- | ---------------------------------- |
| **Terraform** | Tạo AWS resources (VPC, EKS, RDS, MSK, ElastiCache, S3, IAM, ECR, Route53) | Provision lần đầu + thay đổi infra |
| **Ansible**   | Cấu hình OS của EKS nodes (security hardening, sysctl, NVIDIA driver)      | Sau khi Terraform tạo nodes        |
| **Helm**      | Định nghĩa Kubernetes workloads                                            | ArgoCD tự sync; dev dùng trực tiếp |
| **ArgoCD**    | GitOps CD — tự động sync khi Git thay đổi                                  | Liên tục (tự chạy)                 |
| **Jenkins**   | CI — build, test, push image, cập nhật GitOps repo                         | Mỗi khi push code                  |

---

## 2. Cách secrets được quản lý

Đây là phần quan trọng nhất. **Không có password nào được hardcode** trong code hay Helm values.

### Luồng hoàn chỉnh

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  1. Terraform tạo secret trong AWS Secrets Manager                  │
│     Path: sssm/prod/<service-name>                                  │
│     Ví dụ: sssm/prod/user-service                                   │
│                                                                     │
│  2. External Secrets Operator (ESO) chạy trong K8S                  │
│     - Dùng IRSA (IAM Role for Service Account) để đọc Secrets Mgr   │
│     - Không cần AWS Access Key/Secret Key trong pod                 │
│                                                                     │
│  3. ExternalSecret manifest (trong Helm chart của service)          │
│     - Khai báo: "tôi cần secret từ path sssm/prod/user-service"     │
│     - ESO đọc từ AWS → tạo K8S Secret với cùng tên                  │
│                                                                     │
│  4. Pod đọc K8S Secret qua envFrom                                  │
│     - Tất cả keys trong secret → biến môi trường                    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Bước 2a — IRSA hoạt động như thế nào

Terraform tạo IAM Role với trust policy cho service account của ESO:

```hcl
# terraform/modules/eks/main.tf
module "external_secrets_irsa" {
  # Role này chỉ có thể được assume bởi
  # serviceAccount "external-secrets" trong namespace "external-secrets"
  namespace_service_accounts = ["external-secrets:external-secrets"]

  # Role này được phép đọc tất cả secrets có path sssm/prod/*
  external_secrets_secrets_manager_arns = [
    "arn:aws:secretsmanager:*:*:secret:sssm/prod/*"
  ]
}
```

Không có Access Key nào được lưu trong cluster. Kubernetes pod dùng projected service account token để exchange lấy AWS credentials tạm thời.

### Bước 2b — ClusterSecretStore

File `ansible/files/external-secret-store.yaml` được apply một lần trong `cluster-bootstrap.yaml`:

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: aws-secrets-manager
spec:
  provider:
    aws:
      service: SecretsManager
      region: us-east-1
      auth:
        jwt:
          serviceAccountRef:
            name: external-secrets # ServiceAccount có IRSA annotation
            namespace: external-secrets
```

### Bước 2c — ExternalSecret trong mỗi service

Mỗi service có file `externalsecret.yaml` trong Helm chart:

```yaml
# Ví dụ: helm/charts/sssm-services/charts/user-service/templates/externalsecret.yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: user-service-secrets
  namespace: sssm
spec:
  refreshInterval: 1h # ESO tự refresh mỗi giờ
  secretStoreRef:
    name: aws-secrets-manager # Dùng ClusterSecretStore ở trên
    kind: ClusterSecretStore
  target:
    name: user-service-secrets # Tên K8S Secret được tạo ra
    creationPolicy: Owner
  dataFrom:
    - extract:
        key: sssm/prod/user-service # Path trong AWS Secrets Manager
```

ESO sẽ đọc toàn bộ JSON object tại path đó và tạo K8S Secret với mỗi key là một env var.

### Bước 2d — Pod tiêu thụ secret

```yaml
# Deployment template
envFrom:
  - secretRef:
      name: user-service-secrets # K8S Secret do ESO tạo
```

Kết quả: `DB_HOST`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`... đều trở thành env vars trong container.

### Nội dung secret theo service

Khi Terraform chạy, nó tự tạo và điền các secrets sau:

| AWS Secrets Manager Path            | Chứa gì                                                                                                                                                                                          |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `sssm/prod/user-service`            | DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, REDIS_HOST, REDIS_PASSWORD, KAFKA_BOOTSTRAP_SERVERS, KAFKA_SASL_USERNAME, KAFKA_SASL_PASSWORD, KEYCLOAK_ADMIN_URL, KEYCLOAK_ADMIN_CLIENT_SECRET |
| `sssm/prod/post-service`            | DB*HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, REDIS_HOST, REDIS_PASSWORD, KAFKA*\*                                                                                                            |
| `sssm/prod/comment-service`         | CASSANDRA*CONTACT_POINTS, CASSANDRA_PORT, CASSANDRA_KEYSPACE, CASSANDRA_USERNAME, CASSANDRA_PASSWORD, REDIS*\_, KAFKA\_\_                                                                        |
| `sssm/prod/post-guard-service`      | DB*\*, REDIS*\_, KAFKA\_\_, MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY                                                                                                                   |
| `sssm/prod/rds/<db-name>`           | username, password, host, port, dbname (tạo bởi Terraform RDS module)                                                                                                                            |
| `sssm/prod/redis/auth`              | auth_token                                                                                                                                                                                       |
| `sssm/prod/kafka/credentials`       | username, password, bootstrap_brokers                                                                                                                                                            |
| `sssm/prod/opensearch/credentials`  | endpoint                                                                                                                                                                                         |
| `sssm/prod/jenkins/aws-credentials` | aws_access_key_id, aws_secret_access_key                                                                                                                                                         |

> **Lưu ý:** Các secrets cho Keycloak, Cloudinary, FCM/APNs, và các third-party khác phải được tạo **thủ công** trong AWS Secrets Manager sau khi Terraform chạy. Xem [Thêm secret mới](#6-thêm-secret-mới).

---

## 3. Chuẩn bị môi trường

### Cài đặt tools

```bash
# macOS
brew install terraform ansible helm kubectl awscli argocd jq yq

# Verify versions
terraform --version   # >= 1.7
ansible   --version   # >= 2.17 (ansible-core)
helm      version     # >= 3.15
kubectl   version     # >= 1.30
aws       --version   # >= 2.x
yq        --version   # >= 4.x (mikefarah/yq — YAML processor dùng trong CI)
```

### Cấu hình AWS CLI

```bash
# Tạo AWS profile cho từng môi trường
aws configure --profile sssm-prod
# AWS Access Key ID: <IAM user có quyền AdministratorAccess>
# AWS Secret Access Key: <secret>
# Default region: us-east-1
# Default output: json

aws configure --profile sssm-staging
aws configure --profile sssm-dev

# Kiểm tra
aws sts get-caller-identity --profile sssm-prod
```

### Tạo S3 bucket và DynamoDB table cho Terraform state (một lần duy nhất)

```bash
# S3 bucket lưu state
aws s3 mb s3://sssm-terraform-state --region us-east-1 --profile sssm-prod

aws s3api put-bucket-versioning \
  --bucket sssm-terraform-state \
  --versioning-configuration Status=Enabled \
  --profile sssm-prod

aws s3api put-bucket-encryption \
  --bucket sssm-terraform-state \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' \
  --profile sssm-prod

# DynamoDB table cho state locking
aws dynamodb create-table \
  --table-name sssm-terraform-locks \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1 \
  --profile sssm-prod
```

### Cập nhật account ID trong config

Sau khi có AWS account, thay thế `123456789` bằng account ID thực:

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --profile sssm-prod --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# Thay thế trong tất cả file liên quan
grep -r "123456789" infrastructure/ --include="*.yaml" --include="*.groovy" --include="*.tf" -l
# Thay từng file hoặc dùng sed:
find infrastructure/ -type f \( -name "*.yaml" -o -name "*.groovy" -o -name "*.tf" \) \
  -exec sed -i "s/123456789/$ACCOUNT_ID/g" {} +
```

---

## 4. Provision từ đầu (First-time setup)

### Option A — Tự động (khuyến nghị)

```bash
# Provision toàn bộ môi trường prod trong một lệnh
chmod +x infrastructure/scripts/bootstrap.sh
./infrastructure/scripts/bootstrap.sh prod
```

Script này chạy 4 bước theo thứ tự: Terraform → kubeconfig → Ansible → ArgoCD bootstrap.

### Option B — Từng bước thủ công

#### Bước 1 — Terraform: tạo AWS infrastructure

```bash
cd infrastructure/terraform/environments/prod

# Tải providers và kết nối S3 backend
terraform init

# Xem trước những gì sẽ được tạo (~25 phút để apply)
terraform plan -var-file=terraform.tfvars -out=tfplan

# Tạo: VPC, EKS, RDS Aurora, MSK, ElastiCache, OpenSearch, S3, ECR, IAM, Route53, ACM
terraform apply tfplan

# Lưu outputs để dùng ở bước sau
terraform output -json > /tmp/tf-outputs-prod.json
```

**Kiểm tra sau khi apply:**

```bash
# EKS cluster đã tạo chưa?
aws eks list-clusters --region us-east-1 --profile sssm-prod

# ECR repos đã tạo chưa?
aws ecr describe-repositories --region us-east-1 --profile sssm-prod

# Secrets đã có trong Secrets Manager chưa?
aws secretsmanager list-secrets \
  --filter Key=name,Values=sssm/prod \
  --region us-east-1 \
  --profile sssm-prod
```

#### Bước 2 — Thêm secrets của third-party vào AWS Secrets Manager

Terraform tạo các secrets cho database, Redis, Kafka. Nhưng các secrets sau cần điền **thủ công** vì chúng đến từ provider bên ngoài:

```bash
# Keycloak admin credentials (cho user-service)
aws secretsmanager put-secret-value \
  --secret-id sssm/prod/user-service \
  --secret-string "$(
    aws secretsmanager get-secret-value \
      --secret-id sssm/prod/user-service \
      --query SecretString --output text | \
    jq '. + {
      "KEYCLOAK_ADMIN_URL": "https://auth.sssm.com",
      "KEYCLOAK_ADMIN_CLIENT_SECRET": "THAY_BANG_SECRET_THUC"
    }'
  )" \
  --region us-east-1 \
  --profile sssm-prod

# Cloudinary credentials (cho media-service)
aws secretsmanager create-secret \
  --name sssm/prod/cloudinary \
  --secret-string '{
    "CLOUDINARY_CLOUD_NAME": "your-cloud-name",
    "CLOUDINARY_API_KEY": "your-api-key",
    "CLOUDINARY_API_SECRET": "your-api-secret"
  }' \
  --region us-east-1 --profile sssm-prod

# FCM / APNs (cho message-notification-service)
aws secretsmanager create-secret \
  --name sssm/prod/push-notifications \
  --secret-string '{
    "FCM_SERVER_KEY": "your-fcm-key",
    "APNS_KEY_ID": "your-apns-key-id",
    "APNS_TEAM_ID": "your-team-id"
  }' \
  --region us-east-1 --profile sssm-prod
```

> Xem thêm cách thêm secret bất kỳ ở [Mục 6](#6-thêm-secret-mới).

#### Bước 3 — Cập nhật kubeconfig

```bash
aws eks update-kubeconfig \
  --region us-east-1 \
  --name sssm-prod \
  --profile sssm-prod

# Kiểm tra
kubectl get nodes
# Sẽ thấy system, app, infra node groups
```

#### Bước 4 — Ansible: harden EKS nodes

```bash
cd infrastructure/ansible

# Cài Ansible collections cần thiết
ansible-galaxy collection install \
  amazon.aws \
  community.general \
  ansible.posix \
  kubernetes.core

# Chạy hardening trên tất cả nodes
ansible-playbook \
  -i inventories/prod/ \
  node-hardening.yaml \
  --extra-vars "env=prod"

# Chạy bootstrap cluster (tạo namespaces, install ArgoCD)
ansible-playbook \
  -i inventories/prod/ \
  cluster-bootstrap.yaml \
  --extra-vars "env=prod"
```

#### Bước 5 — Verify ArgoCD đang sync

```bash
# Xem trạng thái tất cả ArgoCD applications
kubectl get applications -n argocd

# Chờ tất cả applications về Healthy
kubectl wait application --all \
  --for=condition=Healthy \
  -n argocd \
  --timeout=600s

# Truy cập ArgoCD UI
kubectl port-forward svc/argocd-server -n argocd 8080:443
# Mở browser: https://localhost:8080
# Username: admin
# Password:
kubectl get secret argocd-initial-admin-secret \
  -n argocd \
  -o jsonpath='{.data.password}' | base64 -d
```

ArgoCD sẽ tự động deploy tất cả applications theo thứ tự:

1. `platform` — cert-manager, external-secrets, cluster-autoscaler
2. `ingress` — AWS Load Balancer Controller, NGINX
3. `monitoring` — Prometheus, Grafana, Loki, Zipkin
4. `sssm-infra` — Cassandra, Qdrant, MLflow, MinIO
5. `sssm-services` — 13 microservices

#### Bước 6 — Verify services đang chạy

```bash
# Kiểm tra tất cả pods trong namespace sssm
kubectl get pods -n sssm

# Kiểm tra External Secrets đã tạo K8S Secrets chưa
kubectl get secrets -n sssm
# Phải thấy: user-service-secrets, post-service-secrets, ...

# Kiểm tra ExternalSecret status
kubectl get externalsecrets -n sssm
# STATUS phải là "SecretSynced"

# Nếu có secret bị lỗi, xem chi tiết
kubectl describe externalsecret user-service-secrets -n sssm
```

---

## 5. CI/CD Flow

### Khi developer push code

```
git push origin feature/my-feature
        │
        ▼
GitHub Webhook ──► Jenkins (Multibranch Pipeline)
        │
        ├── Checkout
        ├── Build & Unit Test  (mvn package / pytest)
        ├── Integration Test   (Testcontainers)
        ├── SonarQube          (chỉ branch main/develop)
        ├── Docker Build & Push to ECR  (chỉ main/develop)
        │     Image tag: v<buildNumber>-<commitShort>  (main)
        │              : <branch>-<commitShort>        (develop)
        ├── Helm Lint
        └── Update GitOps  (chỉ main)
              └── Commit vào values-override.yaml:
                  sssm-services.user-service.image.tag: v42-abc1234
                        │
                        ▼
                  ArgoCD phát hiện commit
                        │
                        ▼
                  helm upgrade sssm-services
                        │
                        ▼
                  K8S RollingUpdate (maxUnavailable=0)
```

### Thiết lập Jenkins lần đầu

```bash
# 1. Deploy Jenkins vào cluster (dùng Helm hoặc EC2 tùy chọn)
#    Đây là bước manual — Jenkins không tự deploy chính nó

# 2. Đăng ký Shared Library trong Jenkins UI:
#    Manage Jenkins → Configure System → Global Pipeline Libraries
#    Xem chi tiết: jenkins/config/JENKINS.md

# 3. Thêm credentials vào Jenkins:
#    Manage Jenkins → Credentials → System → Global credentials
#    Cần: github-credentials, sonar-token, aws-prod-credentials,
#         kubeconfig-prod, slack-token
#    Xem danh sách đầy đủ: jenkins/config/JENKINS.md

# 4. Chạy Seed Job để tạo tất cả pipeline jobs:
#    - Tạo job mới kiểu "Pipeline" tên "seed-job"
#    - Pipeline script from SCM, path: infrastructure/jenkins/pipelines/seed-job/Jenkinsfile
#    - Build Now
#    - Sau khi chạy xong: tất cả 13 service pipelines + platform jobs được tạo tự động

# 5. Áp dụng JCasC (optional nhưng khuyến nghị):
#    Manage Jenkins → Configuration as Code → Apply new configuration
#    Upload file: infrastructure/jenkins/config/jenkins.yaml
```

---

## 6. Thêm secret mới

### Tình huống 1 — Thêm secret vào service đã có

Ví dụ: cần thêm `STRIPE_SECRET_KEY` vào `post-service`.

**Bước 1 — Thêm vào AWS Secrets Manager:**

```bash
# Đọc secret hiện tại, merge thêm key mới, ghi lại
CURRENT=$(aws secretsmanager get-secret-value \
  --secret-id sssm/prod/post-service \
  --query SecretString \
  --output text \
  --region us-east-1 \
  --profile sssm-prod)

UPDATED=$(echo "$CURRENT" | jq '. + {"STRIPE_SECRET_KEY": "sk_live_xxxxx"}')

aws secretsmanager put-secret-value \
  --secret-id sssm/prod/post-service \
  --secret-string "$UPDATED" \
  --region us-east-1 \
  --profile sssm-prod
```

**Bước 2 — Thêm key vào Helm values:**

```yaml
# helm/charts/sssm-services/charts/post-service/values.yaml
externalSecrets:
  - secretName: post-service-secrets
    remoteRef: sssm/prod/post-service
    keys:
      - DB_HOST
      - DB_PASSWORD
      # ... các key cũ ...
      - STRIPE_SECRET_KEY # ← thêm dòng này
```

**Bước 3 — Commit và push:** ArgoCD sẽ tự động sync, ESO sẽ cập nhật K8S Secret.

**Bước 4 — Nếu cần áp dụng ngay (không chờ ArgoCD):**

```bash
# Force ESO refresh ngay lập tức
kubectl annotate externalsecret post-service-secrets \
  -n sssm \
  force-sync="$(date +%s)" \
  --overwrite

# Kiểm tra secret đã có key mới chưa
kubectl get secret post-service-secrets -n sssm \
  -o jsonpath='{.data.STRIPE_SECRET_KEY}' | base64 -d

# Restart pod để nhận env var mới
kubectl rollout restart deployment/post-service -n sssm
```

---

### Tình huống 2 — Tạo secret hoàn toàn mới cho service mới

Ví dụ: tạo secrets cho `payment-service` mới.

```bash
# 1. Tạo secret trong AWS Secrets Manager
aws secretsmanager create-secret \
  --name sssm/prod/payment-service \
  --description "payment-service production secrets" \
  --secret-string '{
    "DB_HOST":                  "sssm-prod-pg.cluster-xxxxxx.us-east-1.rds.amazonaws.com",
    "DB_PORT":                  "5432",
    "DB_NAME":                  "sssm_payments",
    "DB_USER":                  "sssm_payments_user",
    "DB_PASSWORD":              "generated-password",
    "REDIS_HOST":               "sssm-prod-redis.xxxxxx.clustercfg.use1.cache.amazonaws.com",
    "REDIS_PORT":               "6379",
    "REDIS_PASSWORD":           "redis-auth-token",
    "KAFKA_BOOTSTRAP_SERVERS":  "b-1.sssm-prod-kafka.xxxxxx.c3.kafka.us-east-1.amazonaws.com:9096",
    "KAFKA_SASL_USERNAME":      "sssm-prod-kafka-user",
    "KAFKA_SASL_PASSWORD":      "kafka-password",
    "STRIPE_SECRET_KEY":        "sk_live_xxxxx",
    "STRIPE_WEBHOOK_SECRET":    "whsec_xxxxx"
  }' \
  --region us-east-1 \
  --profile sssm-prod

# 2. Cho phép ESO role đọc secret này
# (Đã được Terraform cấp cho pattern sssm/prod/* — không cần làm gì thêm)

# 3. Xác nhận ESO có thể đọc
aws secretsmanager get-secret-value \
  --secret-id sssm/prod/payment-service \
  --region us-east-1 \
  --profile sssm-prod \
  --query SecretString \
  --output text | jq .
```

---

### Tình huống 3 — Rotate secret (thay đổi password)

```bash
# Dùng script có sẵn — tự động rotate + restart pod
./infrastructure/scripts/rotate-secrets.sh sssm/prod/user-service prod

# Hoặc dùng Jenkins job:
# SSSM/Platform/rotate-secrets → Build with Parameters
#   SECRET_NAME: sssm/prod/user-service
#   ENVIRONMENT: prod
```

---

### Tình huống 4 — Xem secret hiện tại (debug)

```bash
# Xem raw value trong AWS Secrets Manager
aws secretsmanager get-secret-value \
  --secret-id sssm/prod/user-service \
  --query SecretString \
  --output text \
  --region us-east-1 \
  --profile sssm-prod | jq .

# Xem K8S Secret (base64 encoded)
kubectl get secret user-service-secrets -n sssm -o yaml

# Decode một key cụ thể
kubectl get secret user-service-secrets -n sssm \
  -o jsonpath='{.data.DB_PASSWORD}' | base64 -d

# Kiểm tra trạng thái sync của ExternalSecret
kubectl describe externalsecret user-service-secrets -n sssm
# Tìm dòng: "Status: SecretSynced" hoặc thông báo lỗi
```

---

## 7. Thêm service mới

Ví dụ thêm `payment-service` (Spring Boot, port 8094, PostgreSQL).

### Bước 1 — Terraform: tạo ECR repo và database

```hcl
# terraform/modules/ecr/main.tf — thêm vào list services
locals {
  services = [
    # ... services hiện có ...
    "payment-service",    # ← thêm
  ]
}
```

```hcl
# terraform/modules/rds/main.tf — thêm vào list databases
locals {
  databases = [
    # ... databases hiện có ...
    "sssm_payments",   # ← thêm
  ]
}
```

```bash
cd infrastructure/terraform/environments/prod
terraform plan -var-file=terraform.tfvars
terraform apply
```

### Bước 2 — Tạo secret trong AWS Secrets Manager

Làm theo [Tình huống 2](#tình-huống-2--tạo-secret-hoàn-toàn-mới-cho-service-mới) ở trên.

### Bước 3 — Tạo Helm sub-chart

```bash
# Tạo thư mục chart mới
mkdir -p infrastructure/helm/charts/sssm-services/charts/payment-service/templates

# Tạo Chart.yaml
cat > infrastructure/helm/charts/sssm-services/charts/payment-service/Chart.yaml << 'EOF'
apiVersion: v2
name: payment-service
description: SSSM payment-service
type: application
version: 1.0.0
appVersion: "1.0.0"
EOF

# Tạo values.yaml (copy từ service tương tự và chỉnh sửa)
cp infrastructure/helm/charts/sssm-services/charts/post-service/values.yaml \
   infrastructure/helm/charts/sssm-services/charts/payment-service/values.yaml
# Chỉnh: port, secretName, remoteRef, env vars

# Copy templates
cp -r infrastructure/helm/charts/sssm-services/charts/post-service/templates/ \
      infrastructure/helm/charts/sssm-services/charts/payment-service/templates/
# Chỉnh app name trong deployment.yaml nếu cần
```

### Bước 4 — Đăng ký trong parent chart

```yaml
# helm/charts/sssm-services/Chart.yaml — thêm dependency
dependencies:
  # ... dependencies hiện có ...
  - name: payment-service
    version: "1.0.0"
    repository: "file://charts/payment-service"
    condition: paymentService.enabled

# helm/charts/sssm-services/values.yaml — thêm flag
paymentService: { enabled: true }
```

### Bước 5 — Tạo Jenkinsfile

```bash
mkdir -p infrastructure/jenkins/pipelines/spring-services/payment-service
cat > infrastructure/jenkins/pipelines/spring-services/payment-service/Jenkinsfile << 'EOF'
@Library('sssm-shared-library') _

springBootPipeline(
    serviceName: 'payment-service',
    servicePort:  8094
)
EOF
```

### Bước 6 — Đăng ký Jenkins job

Thêm vào `jenkins/pipelines/seed-job/jobs.groovy`:

```groovy
def springServices = [
    // ... services hiện có ...
    [name: 'payment-service', port: 8094],   // ← thêm
]
```

Chạy lại Seed Job trong Jenkins để tạo pipeline mới.

### Bước 7 — Cập nhật ArgoCD Image Updater

```yaml
# argocd/applications/sssm-services.yaml — thêm vào image-list
argocd-image-updater.argoproj.io/image-list: >-
  # ... images hiện có ...
  payment-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/payment-service
```

---

## 8. Day-2 Operations

### Scale service thủ công

```bash
# Scale tạm thời (ArgoCD sẽ revert nếu ignoreDifferences không config cho replicas)
kubectl scale deployment user-service -n sssm --replicas=5

# Scale vĩnh viễn → sửa values-override.yaml và commit
# helm/environments/prod/values-override.yaml:
#   user-service:
#     autoscaling:
#       minReplicas: 5
```

### Scale EKS node group

```hcl
# terraform/modules/eks/main.tf
app = {
  min_size     = 6    # tăng từ 4
  desired_size = 8    # tăng từ 6
  max_size     = 30   # tăng từ 20
}
```

```bash
cd infrastructure/terraform/environments/prod
terraform apply -target=module.eks
```

### Rolling restart service

```bash
kubectl rollout restart deployment/user-service -n sssm
kubectl rollout status  deployment/user-service -n sssm
```

### Rollback service về image cũ

```bash
# Xem lịch sử Helm releases
helm history sssm-services -n sssm

# Rollback về revision trước
helm rollback sssm-services 2 -n sssm

# Hoặc: sửa image.tag trong values-override.yaml về version cũ và commit
# ArgoCD sẽ tự apply
```

### Truy cập Grafana

```bash
kubectl port-forward svc/grafana -n monitoring 3000:80
# Mở http://localhost:3000
# User: admin / Pass: xem trong secret grafana
kubectl get secret monitoring-grafana -n monitoring \
  -o jsonpath='{.data.admin-password}' | base64 -d
```

### Truy cập ArgoCD UI

```bash
kubectl port-forward svc/argocd-server -n argocd 8080:443
# Mở https://localhost:8080
```

### Kiểm tra logs tập trung (Loki + Grafana)

```bash
# Trong Grafana → Explore → Loki
# Query ví dụ:
{namespace="sssm", app="user-service"} |= "ERROR"
{namespace="sssm"} | json | level="ERROR" | line_format "{{.message}}"
```

### Upgrade Terraform provider

```bash
# Sửa version constraint trong terraform/environments/prod/main.tf
# Sau đó:
cd infrastructure/terraform/environments/prod
terraform init -upgrade
terraform plan -var-file=terraform.tfvars
```

### Upgrade Helm chart dependency

```bash
# Sửa version trong Chart.yaml của chart tương ứng, sau đó:
helm dependency update infrastructure/helm/charts/sssm-services/
# Commit lock file (Chart.lock) vào Git
```

### Kiểm tra trạng thái secrets trong cluster

```bash
# Xem tất cả ExternalSecrets
kubectl get externalsecrets -n sssm

# Xem chi tiết một secret bị lỗi
kubectl describe externalsecret <name> -n sssm

# Force sync tất cả ExternalSecrets
for es in $(kubectl get externalsecrets -n sssm -o name); do
  kubectl annotate $es -n sssm force-sync="$(date +%s)" --overwrite
done
```

---

## 9. Cấu trúc thư mục

```
infrastructure/
│
├── terraform/                          # AWS infrastructure as code
│   ├── environments/
│   │   ├── dev/                        # Dev environment (t4g instances, 1 NAT GW)
│   │   │   ├── main.tf                 # Module calls + S3 backend (dev state)
│   │   │   ├── variables.tf
│   │   │   └── terraform.tfvars        # Dev sizing: db.t4g.medium, 10.1.0.0/16
│   │   ├── staging/                    # Staging environment
│   │   └── prod/                       # Production environment (HA, multi-AZ)
│   │       ├── main.tf
│   │       ├── variables.tf
│   │       └── terraform.tfvars        # Prod sizing: db.r6g.large, 10.0.0.0/16
│   └── modules/
│       ├── vpc/                        # VPC, subnets, NAT GW, VPC endpoints
│       ├── eks/                        # EKS cluster + 4 node groups + IRSA roles
│       ├── rds/                        # Aurora PostgreSQL, per-service databases
│       ├── elasticache/                # Redis 7 cluster mode
│       ├── msk/                        # Kafka 3.7, SASL/SCRAM, KMS
│       ├── opensearch/                 # OpenSearch 2.x, IRSA auth
│       ├── ecr/                        # ECR repos + lifecycle policies
│       ├── s3/                         # State, MLflow, backups, logs buckets
│       ├── iam/                        # IRSA policies (MLflow, media-service, Jenkins)
│       └── dns/                        # Route53 + ACM wildcard cert
│
├── ansible/                            # EKS node OS configuration
│   ├── ansible.cfg
│   ├── node-hardening.yaml             # Main playbook (tất cả nodes)
│   ├── cluster-bootstrap.yaml          # One-time: namespaces + ArgoCD install
│   ├── files/
│   │   └── external-secret-store.yaml  # ClusterSecretStore manifest
│   ├── inventories/
│   │   ├── dev/                        # aws_ec2.yaml (dynamic inventory)
│   │   ├── staging/
│   │   └── prod/
│   │       ├── aws_ec2.yaml            # Discover nodes by tag Project=sssm, Env=prod
│   │       └── group_vars/all.yaml     # Env vars: cluster name, node exporter version...
│   └── roles/
│       ├── common/                     # Packages, NTP, ulimits, sysctl tuning
│       ├── security-hardening/         # SSH, fail2ban, auditd, firewalld
│       ├── eks-node/                   # containerd config, kubelet args, log rotation
│       ├── monitoring-agent/           # CloudWatch agent, Prometheus node exporter
│       └── gpu-driver/                 # NVIDIA driver + CUDA (ai-gpu nodes only)
│
├── helm/                               # Kubernetes workload definitions
│   ├── charts/
│   │   ├── sssm-services/              # Parent chart, 13 service sub-charts
│   │   │   ├── Chart.yaml              # Dependencies list
│   │   │   ├── values.yaml             # Global defaults (registry, resources, tracing)
│   │   │   └── charts/
│   │   │       ├── user-service/       # Deployment, Service, HPA, PDB, SA, ExternalSecret
│   │   │       ├── post-service/
│   │   │       ├── ...                 # (9 Spring Boot + 4 FastAPI services)
│   │   │       └── ai-dashboard-service/
│   │   ├── sssm-infra/                 # Cassandra, Qdrant, MLflow, MinIO
│   │   ├── monitoring/                 # kube-prometheus-stack, Loki, Zipkin
│   │   ├── ingress/                    # AWS LB Controller, NGINX Ingress
│   │   └── platform/                   # cert-manager, external-secrets, cluster-autoscaler
│   └── environments/
│       ├── dev/values-override.yaml    # replicaCount: 1, autoscaling: off
│       ├── staging/values-override.yaml
│       └── prod/values-override.yaml   # replicaCount: 3, minReplicas: 3, max resources
│
├── argocd/
│   ├── bootstrap/
│   │   ├── app-of-apps.yaml            # Root Application — apply một lần duy nhất
│   │   ├── sssm-project.yaml           # ArgoCD Project với RBAC
│   │   ├── image-updater-config.yaml   # ECR credentials cho Image Updater
│   │   └── notifications-config.yaml   # Slack alerts khi deploy/degrade
│   └── applications/
│       ├── platform.yaml               # Sync: automated, prune: true
│       ├── ingress.yaml
│       ├── monitoring.yaml
│       ├── sssm-infra.yaml             # Sync: automated, prune: FALSE (stateful)
│       └── sssm-services.yaml          # Image Updater annotations cho 13 services
│
├── jenkins/
│   ├── shared-library/                 # Global reusable pipeline logic
│   │   ├── vars/
│   │   │   ├── springBootPipeline.groovy   # Pipeline hoàn chỉnh cho Spring Boot
│   │   │   ├── fastApiPipeline.groovy      # Pipeline hoàn chỉnh cho FastAPI
│   │   │   ├── dockerBuildPush.groovy      # ECR login + build + push
│   │   │   ├── updateGitOps.groovy         # Commit image.tag vào GitOps repo
│   │   │   ├── helmLint.groovy             # helm lint + template dry-run
│   │   │   └── notifySlack.groovy          # Centralized Slack notification
│   │   └── src/io/github/lvoxx/jenkins/
│   │       ├── DockerUtils.groovy      # Image name/tag helpers
│   │       └── GitUtils.groovy         # Branch/commit helpers
│   ├── pipelines/
│   │   ├── seed-job/
│   │   │   ├── Jenkinsfile             # Chạy jobs.groovy
│   │   │   └── jobs.groovy             # Job DSL: tạo tất cả pipeline jobs
│   │   ├── spring-services/            # 9 Jenkinsfiles (mỗi file ~5 dòng)
│   │   ├── ai-services/                # 4 Jenkinsfiles
│   │   └── platform/
│   │       ├── helm-lint-all/          # Lint tất cả charts song song
│   │       ├── terraform-plan/         # Plan + post lên PR comment
│   │       ├── nightly-tests/          # Chạy 02:xx UTC mỗi đêm
│   │       └── rotate-secrets/         # Rotate AWS secret + restart pod
│   ├── docker/
│   │   ├── spring-service.Dockerfile   # Multi-stage: Maven build → JRE Alpine
│   │   └── fastapi-service.Dockerfile  # Multi-stage: pip install → Python slim
│   └── config/
│       ├── JENKINS.md                  # Plugins, credentials, agent labels
│       └── jenkins.yaml                # JCasC: shared lib, global env, SonarQube, Slack
│
└── scripts/
    ├── bootstrap.sh                    # All-in-one: Terraform → Ansible → ArgoCD
    └── rotate-secrets.sh               # AWS rotation + ESO refresh + rolling restart
```

---

## Ghi chú quan trọng

- **Không commit secrets vào Git** — mọi thứ đi qua AWS Secrets Manager
- **`sssm-infra` không bao giờ auto-prune** — `prune: false` để tránh xóa Cassandra/MinIO
- **Database schema không được apply bởi services** — chỉ qua K8S Jobs (Flyway/cqlsh/curl)
- **`spring.cassandra.schema-action` luôn là `NONE`** trong application.yaml
- **ECR Image Updater chỉ update tag theo semver** — tag phải bắt đầu bằng `v` (vd: `v42-abc1234`)
- **Prod EKS endpoint là private** — phải dùng VPN hoặc bastion để truy cập kubectl trên prod
