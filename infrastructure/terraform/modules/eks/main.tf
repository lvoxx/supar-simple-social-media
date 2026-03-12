locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = "${var.project}-${var.env}"
  cluster_version = var.eks_cluster_version

  vpc_id                          = var.vpc_id
  subnet_ids                      = var.private_subnet_ids
  control_plane_subnet_ids        = var.private_subnet_ids
  cluster_endpoint_private_access = true
  cluster_endpoint_public_access  = var.env != "prod"

  enable_irsa = true

  cluster_addons = {
    coredns            = { most_recent = true }
    kube-proxy         = { most_recent = true }
    vpc-cni            = { most_recent = true }
    aws-ebs-csi-driver = { most_recent = true }
    aws-efs-csi-driver = { most_recent = true }
  }

  eks_managed_node_groups = {
    system = {
      name           = "system"
      instance_types = ["m6i.large"]
      capacity_type  = "ON_DEMAND"
      min_size       = 2
      desired_size   = 2
      max_size       = 4
      disk_size      = 50
      labels         = { role = "system" }
      taints = [{
        key    = "CriticalAddonsOnly"
        value  = "true"
        effect = "NO_SCHEDULE"
      }]
    }

    app = {
      name           = "app"
      instance_types = ["c6i.xlarge", "c6i.2xlarge"]
      capacity_type  = "SPOT"
      min_size       = 4
      desired_size   = 6
      max_size       = 20
      disk_size      = 50
      labels         = { role = "app" }
    }

    infra = {
      name           = "infra"
      instance_types = ["r6i.xlarge", "r6i.2xlarge"]
      capacity_type  = "ON_DEMAND"
      min_size       = 3
      desired_size   = 3
      max_size       = 10
      disk_size      = 100
      labels         = { role = "infra" }
      taints = [{
        key    = "role"
        value  = "infra"
        effect = "NO_SCHEDULE"
      }]
    }

    ai-gpu = {
      name           = "ai-gpu"
      instance_types = ["g4dn.xlarge"]
      capacity_type  = "SPOT"
      min_size       = 0
      desired_size   = 0
      max_size       = 4
      disk_size      = 100
      labels         = { role = "ai-gpu", "nvidia.com/gpu" = "true" }
      taints = [{
        key    = "nvidia.com/gpu"
        value  = "true"
        effect = "NO_SCHEDULE"
      }]
      pre_bootstrap_user_data = <<-EOT
        #!/bin/bash
        /etc/eks/bootstrap.sh ${var.project}-${var.env}
      EOT
    }
  }

  tags = local.common_tags
}

# IRSA — AWS Load Balancer Controller
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

# IRSA — Cluster Autoscaler
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

# IRSA — External Secrets Operator
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
