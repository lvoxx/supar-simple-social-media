# Terraform — AWS Infrastructure

**Provider:** `hashicorp/aws ~> 5.0`  
**Backend:** S3 + DynamoDB locking  
**State:** per-environment, isolated workspaces  
**Minimum Terraform version:** 1.7

---

## Backend configuration

Each environment has its own state file in S3.

```hcl
# terraform/environments/prod/main.tf  (backend block)
terraform {
  required_version = ">= 1.7"

  required_providers {
    aws        = { source = "hashicorp/aws",        version = "~> 5.0"  }
    kubernetes = { source = "hashicorp/kubernetes",  version = "~> 2.30" }
    helm       = { source = "hashicorp/helm",        version = "~> 2.14" }
    random     = { source = "hashicorp/random",      version = "~> 3.6"  }
  }

  backend "s3" {
    bucket         = "sssm-terraform-state"
    key            = "prod/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "sssm-terraform-locks"
    profile        = "sssm-prod"
  }
}
```

---

## Module overview

| Module | Resources created | Approx cost driver |
|--------|------------------|--------------------|
| `vpc` | VPC, 3× public + private + data subnets, IGW, NAT GW, route tables, VPC endpoints (S3, ECR, Secrets Manager) | NAT Gateway ($0.045/h × 3) |
| `eks` | EKS control plane, 4 managed node groups, OIDC provider, cluster IAM role, node IAM roles, aws-auth ConfigMap | EC2 instance hours |
| `rds` | RDS Aurora PostgreSQL 16, multi-AZ, 1 writer + 2 readers, automated backups, enhanced monitoring | Instance hours + storage |
| `elasticache` | ElastiCache Redis 7 cluster mode, 3 shards × 2 replicas | Instance hours |
| `msk` | Amazon MSK Kafka 3.7, 3 brokers (one per AZ), encrypted, SASL/SCRAM auth | Instance hours + storage |
| `opensearch` | Amazon OpenSearch Service 2.x (replaces self-hosted ES), 3 data nodes, dedicated master | Instance hours + storage |
| `s3` | terraform-state, mlflow-artifacts, media-backups, logs buckets (lifecycle + versioning) | Storage + requests |
| `ecr` | One ECR repo per service (13 services + 4 AI services), image scanning, lifecycle policy | Storage (minimal) |
| `iam` | IRSA roles (cert-manager, external-secrets, cluster-autoscaler, load-balancer-controller, MLflow, RDS proxy) | Free |
| `dns` | Route 53 hosted zone, A records for ALB, certificate validation CNAMEs | Hosted zone $0.50/mo |

---

## Module: vpc

```hcl
# terraform/modules/vpc/main.tf

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.8"

  name = "${var.project}-${var.env}"
  cidr = var.vpc_cidr   # "10.0.0.0/16"

  azs              = var.availability_zones  # ["us-east-1a","us-east-1b","us-east-1c"]
  public_subnets   = var.public_subnets      # ["10.0.0.0/20","10.0.16.0/20","10.0.32.0/20"]
  private_subnets  = var.private_subnets     # ["10.0.48.0/20","10.0.64.0/20","10.0.80.0/20"]
  database_subnets = var.database_subnets    # ["10.0.96.0/20","10.0.112.0/20","10.0.128.0/20"]

  enable_nat_gateway     = true
  single_nat_gateway     = var.env == "dev"  # dev: 1 NAT, prod/staging: 3
  one_nat_gateway_per_az = var.env != "dev"

  enable_dns_hostnames = true
  enable_dns_support   = true

  # VPC endpoints — avoid NAT costs for ECR, S3, Secrets Manager
  enable_s3_endpoint              = true
  enable_secretsmanager_endpoint  = true
  enable_ecr_api_endpoint         = true
  enable_ecr_dkr_endpoint         = true

  # Required tags for EKS to auto-discover subnets
  public_subnet_tags = {
    "kubernetes.io/role/elb"                        = "1"
    "kubernetes.io/cluster/${var.project}-${var.env}" = "shared"
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb"               = "1"
    "kubernetes.io/cluster/${var.project}-${var.env}" = "shared"
  }

  tags = local.common_tags
}
```

---

## Module: eks

```hcl
# terraform/modules/eks/main.tf

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = "${var.project}-${var.env}"
  cluster_version = "1.30"

  vpc_id                         = var.vpc_id
  subnet_ids                     = var.private_subnet_ids
  control_plane_subnet_ids       = var.private_subnet_ids
  cluster_endpoint_private_access = true
  cluster_endpoint_public_access  = var.env != "prod"   # prod: private only

  # Enable IRSA (IAM Roles for Service Accounts)
  enable_irsa = true

  # Cluster addons — managed by AWS
  cluster_addons = {
    coredns                = { most_recent = true }
    kube-proxy             = { most_recent = true }
    vpc-cni                = { most_recent = true }
    aws-ebs-csi-driver     = { most_recent = true }
    aws-efs-csi-driver     = { most_recent = true }
  }

  eks_managed_node_groups = {

    # System workloads: kube-system, ArgoCD, monitoring
    system = {
      name           = "system"
      instance_types = ["m6i.large"]
      capacity_type  = "ON_DEMAND"
      min_size       = 2
      desired_size   = 2
      max_size       = 4
      disk_size      = 50

      labels = { role = "system" }
      taints = [{
        key    = "CriticalAddonsOnly"
        value  = "true"
        effect = "NO_SCHEDULE"
      }]
    }

    # Application services (Spring Boot + FastAPI)
    app = {
      name           = "app"
      instance_types = ["c6i.xlarge", "c6i.2xlarge"]
      capacity_type  = "SPOT"       # cost optimisation
      min_size       = 4
      desired_size   = 6
      max_size       = 20
      disk_size      = 50

      labels = { role = "app" }
    }

    # Infrastructure workloads: Kafka, Cassandra, Redis (self-managed)
    infra = {
      name           = "infra"
      instance_types = ["r6i.xlarge", "r6i.2xlarge"]
      capacity_type  = "ON_DEMAND"
      min_size       = 3
      desired_size   = 3
      max_size       = 10
      disk_size      = 100

      labels = { role = "infra" }
      taints = [{
        key    = "role"
        value  = "infra"
        effect = "NO_SCHEDULE"
      }]
    }

    # GPU nodes for AI inference (scale to 0 when idle)
    ai-gpu = {
      name           = "ai-gpu"
      instance_types = ["g4dn.xlarge"]   # 1× T4 GPU, 4 vCPU, 16 GB RAM
      capacity_type  = "SPOT"
      min_size       = 0
      desired_size   = 0
      max_size       = 4
      disk_size      = 100

      labels  = { role = "ai-gpu", "nvidia.com/gpu" = "true" }
      taints  = [{
        key    = "nvidia.com/gpu"
        value  = "true"
        effect = "NO_SCHEDULE"
      }]

      # NVIDIA GPU operator will install the device plugin
      pre_bootstrap_user_data = <<-EOT
        #!/bin/bash
        /etc/eks/bootstrap.sh ${var.project}-${var.env}
      EOT
    }
  }

  tags = local.common_tags
}

# IRSA for AWS Load Balancer Controller
module "lb_controller_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name                              = "${var.project}-${var.env}-lb-controller"
  attach_load_balancer_controller_policy = true

  oidc_providers = {
    ex = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}

# IRSA for Cluster Autoscaler
module "cluster_autoscaler_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name                        = "${var.project}-${var.env}-cluster-autoscaler"
  attach_cluster_autoscaler_policy = true
  cluster_autoscaler_cluster_names = [module.eks.cluster_name]

  oidc_providers = {
    ex = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:cluster-autoscaler"]
    }
  }
}

# IRSA for External Secrets Operator (reads AWS Secrets Manager)
module "external_secrets_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name                             = "${var.project}-${var.env}-external-secrets"
  attach_external_secrets_policy        = true
  external_secrets_ssm_parameter_arns   = ["arn:aws:ssm:*:*:parameter/${var.project}/${var.env}/*"]
  external_secrets_secrets_manager_arns = ["arn:aws:secretsmanager:*:*:secret:${var.project}/${var.env}/*"]

  oidc_providers = {
    ex = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["external-secrets:external-secrets"]
    }
  }
}
```

---

## Module: rds

```hcl
# terraform/modules/rds/main.tf
# Aurora PostgreSQL 16 — HA writer + read replicas per service database

# Each Spring Boot / FastAPI service that uses PostgreSQL gets its own
# database on the shared Aurora cluster (not a separate cluster per service).
# Database-level isolation is enforced via separate users and schemas.

module "aurora_postgresql" {
  source  = "terraform-aws-modules/rds-aurora/aws"
  version = "~> 9.3"

  name              = "${var.project}-${var.env}-pg"
  engine            = "aurora-postgresql"
  engine_version    = "16.2"
  instance_class    = var.rds_instance_class  # db.r6g.large (prod), db.t4g.medium (dev)

  vpc_id               = var.vpc_id
  db_subnet_group_name = aws_db_subnet_group.this.name
  security_group_ids   = [aws_security_group.rds.id]

  instances = {
    writer   = {}
    reader-1 = { promotion_tier = 1 }
    reader-2 = { promotion_tier = 2, availability_zone = var.azs[2] }
  }

  storage_encrypted   = true
  deletion_protection = var.env == "prod"

  backup_retention_period      = var.env == "prod" ? 30 : 7
  preferred_backup_window      = "03:00-04:00"
  preferred_maintenance_window = "sun:04:00-sun:05:00"

  enabled_cloudwatch_logs_exports = ["postgresql"]
  monitoring_interval             = 60
  monitoring_role_arn             = aws_iam_role.rds_monitoring.arn

  # Passwords managed by AWS Secrets Manager
  manage_master_user_password = true

  tags = local.common_tags
}

# Per-service database and user (Terraform manages creation via null_resource + psql)
resource "null_resource" "init_databases" {
  for_each = toset([
    "sssm_users",
    "sssm_media",
    "sssm_posts",
    "sssm_groups",
    "sssm_post_guard",
    "sssm_user_analysis",
    "sssm_ai_dashboard",
  ])

  provisioner "local-exec" {
    command = <<-EOT
      PGPASSWORD=$(aws secretsmanager get-secret-value \
        --secret-id ${module.aurora_postgresql.cluster_master_user_secret[0].secret_arn} \
        --query SecretString --output text | jq -r .password) \
      psql -h ${module.aurora_postgresql.cluster_endpoint} \
           -U ${module.aurora_postgresql.cluster_master_username} \
           -c "CREATE DATABASE ${each.key};" \
           -c "CREATE USER ${each.key}_user WITH ENCRYPTED PASSWORD '${random_password.db_passwords[each.key].result}';" \
           -c "GRANT ALL PRIVILEGES ON DATABASE ${each.key} TO ${each.key}_user;" \
           postgres
    EOT
  }
  depends_on = [module.aurora_postgresql]
}
```

---

## Module: elasticache

```hcl
# terraform/modules/elasticache/main.tf
# Redis 7 — Cluster Mode Enabled, 3 shards × 2 replicas

module "elasticache_redis" {
  source  = "terraform-aws-modules/elasticache/aws"
  version = "~> 1.2"

  cluster_id               = "${var.project}-${var.env}-redis"
  description              = "SSSM Platform Redis"
  engine_version           = "7.1"
  node_type                = var.redis_node_type  # cache.r6g.large (prod), cache.t4g.medium (dev)

  cluster_mode_enabled     = true
  num_node_groups          = 3
  replicas_per_node_group  = var.env == "prod" ? 2 : 1

  subnet_group_name = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled  = true
  transit_encryption_enabled  = true
  auth_token                  = random_password.redis_auth.result
  auth_token_update_strategy  = "ROTATE"

  apply_immediately            = var.env != "prod"
  automatic_failover_enabled   = true
  multi_az_enabled             = var.env == "prod"

  maintenance_window        = "sun:05:00-sun:06:00"
  snapshot_retention_limit  = var.env == "prod" ? 7 : 1
  snapshot_window           = "04:00-05:00"

  log_delivery_configuration = [{
    destination      = aws_cloudwatch_log_group.redis.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }]

  tags = local.common_tags
}
```

---

## Module: msk

```hcl
# terraform/modules/msk/main.tf
# Amazon MSK — Kafka 3.7, 3 brokers (one per AZ)

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.project}-${var.env}-kafka"
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.kafka_instance_type  # kafka.m5.large (prod), kafka.t3.small (dev)
    client_subnets  = var.database_subnet_ids
    storage_info {
      ebs_storage_info { volume_size = var.kafka_storage_gb }
    }
    security_groups = [aws_security_group.msk.id]
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn
  }

  client_authentication {
    sasl { scram = true }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  open_monitoring {
    prometheus {
      jmx_exporter  { enabled_in_broker = true }
      node_exporter { enabled_in_broker = true }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = local.common_tags
}

resource "aws_msk_configuration" "this" {
  name              = "${var.project}-${var.env}-kafka-config"
  kafka_versions    = ["3.7.x"]
  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=12
    log.retention.hours=168
    log.segment.bytes=1073741824
    log.retention.check.interval.ms=300000
    compression.type=lz4
    message.max.bytes=10485760
  EOT
}
```

---

## Module: opensearch

```hcl
# terraform/modules/opensearch/main.tf
# Amazon OpenSearch Service 2.x (replaces self-hosted Elasticsearch 8)
# Note: AWS OpenSearch is Elasticsearch-compatible (fork at ES 7.10)
# All Spring Boot services use the standard ES 8 client with compatibility mode.

resource "aws_opensearch_domain" "this" {
  domain_name    = "${var.project}-${var.env}"
  engine_version = "OpenSearch_2.13"

  cluster_config {
    instance_type            = var.opensearch_instance_type  # r6g.large.search (prod)
    instance_count           = var.env == "prod" ? 3 : 1
    dedicated_master_enabled = var.env == "prod"
    dedicated_master_type    = "r6g.large.search"
    dedicated_master_count   = 3
    zone_awareness_enabled   = var.env == "prod"
    zone_awareness_config {
      availability_zone_count = 3
    }
  }

  ebs_options {
    ebs_enabled = true
    volume_type = "gp3"
    volume_size = var.opensearch_volume_gb
    iops        = 3000
    throughput  = 125
  }

  encrypt_at_rest          { enabled = true }
  node_to_node_encryption  { enabled = true }
  domain_endpoint_options  { enforce_https = true; tls_security_policy = "Policy-Min-TLS-1-2-2019-07" }

  advanced_security_options {
    enabled                        = true
    internal_user_database_enabled = false
    master_user_options {
      master_user_arn = module.opensearch_irsa.iam_role_arn
    }
  }

  vpc_options {
    subnet_ids         = var.env == "prod" ? var.database_subnet_ids : [var.database_subnet_ids[0]]
    security_group_ids = [aws_security_group.opensearch.id]
  }

  log_publishing_options {
    cloudwatch_log_group_arn = aws_cloudwatch_log_group.opensearch.arn
    log_type                 = "INDEX_SLOW_LOGS"
  }

  auto_tune_options {
    desired_state       = "ENABLED"
    rollback_on_disable = "NO_ROLLBACK"
  }

  tags = local.common_tags
}
```

---

## Module: ecr

```hcl
# terraform/modules/ecr/main.tf

locals {
  services = [
    "user-service", "media-service", "post-service",
    "comment-service", "notification-service", "search-service",
    "group-service", "private-message-service", "message-notification-service",
    "post-guard-service", "media-guard-service",
    "user-analysis-service", "ai-dashboard-service",
  ]
}

resource "aws_ecr_repository" "services" {
  for_each             = toset(local.services)
  name                 = "${var.project}/${each.value}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration { scan_on_push = true }
  encryption_configuration    { encryption_type = "AES256" }

  tags = local.common_tags
}

resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = aws_ecr_repository.services
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last 10 production images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["v"]
          countType     = "imageCountMoreThan"
          countNumber   = 10
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Expire untagged images after 7 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      }
    ]
  })
}
```

---

## Environment variables reference

```hcl
# terraform/environments/prod/terraform.tfvars

project    = "sssm"
env        = "prod"
aws_region = "us-east-1"

availability_zones = ["us-east-1a", "us-east-1b", "us-east-1c"]
vpc_cidr           = "10.0.0.0/16"
public_subnets     = ["10.0.0.0/20",  "10.0.16.0/20",  "10.0.32.0/20"]
private_subnets    = ["10.0.48.0/20", "10.0.64.0/20",  "10.0.80.0/20"]
database_subnets   = ["10.0.96.0/20", "10.0.112.0/20", "10.0.128.0/20"]

# EKS
eks_cluster_version = "1.30"

# RDS
rds_instance_class = "db.r6g.large"

# ElastiCache
redis_node_type = "cache.r6g.large"

# MSK
kafka_instance_type = "kafka.m5.large"
kafka_storage_gb    = 1000

# OpenSearch
opensearch_instance_type = "r6g.large.search"
opensearch_volume_gb     = 200

# DNS
domain_name = "sssm.com"
```

---

## Outputs (used by Ansible and Helm)

```hcl
# terraform/modules/eks/outputs.tf
output "cluster_name"       { value = module.eks.cluster_name }
output "cluster_endpoint"   { value = module.eks.cluster_endpoint }
output "cluster_ca"         { value = module.eks.cluster_certificate_authority_data }
output "node_group_role_arn" { value = module.eks.eks_managed_node_groups["app"].iam_role_arn }

# terraform/modules/rds/outputs.tf
output "pg_writer_endpoint" { value = module.aurora_postgresql.cluster_endpoint }
output "pg_reader_endpoint" { value = module.aurora_postgresql.cluster_reader_endpoint }
output "pg_secret_arns"     { value = { for db, pw in aws_secretsmanager_secret.db_passwords : db => pw.arn } }

# terraform/modules/elasticache/outputs.tf
output "redis_configuration_endpoint" { value = module.elasticache_redis.configuration_endpoint_address }
output "redis_auth_secret_arn"         { value = aws_secretsmanager_secret.redis_auth.arn }

# terraform/modules/msk/outputs.tf
output "kafka_bootstrap_brokers_tls" { value = aws_msk_cluster.this.bootstrap_brokers_sasl_scram }
output "kafka_secret_arn"            { value = aws_secretsmanager_secret.msk_credentials.arn }

# terraform/modules/opensearch/outputs.tf
output "opensearch_endpoint"    { value = aws_opensearch_domain.this.endpoint }
output "opensearch_secret_arn"  { value = aws_secretsmanager_secret.opensearch_credentials.arn }

# terraform/modules/ecr/outputs.tf
output "ecr_registry_url" { value = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com" }
output "ecr_repos"        { value = { for k, v in aws_ecr_repository.services : k => v.repository_url } }
```
