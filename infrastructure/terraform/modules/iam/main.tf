locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# MLflow IRSA — access S3 artifacts bucket
module "mlflow_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name = "${var.project}-${var.env}-mlflow"

  role_policy_arns = {
    s3 = aws_iam_policy.mlflow_s3.arn
  }

  oidc_providers = {
    ex = {
      provider_arn               = var.oidc_provider_arn
      namespace_service_accounts = ["sssm-infra:mlflow"]
    }
  }
}

resource "aws_iam_policy" "mlflow_s3" {
  name = "${var.project}-${var.env}-mlflow-s3"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"]
      Resource = [
        "arn:aws:s3:::${var.project}-${var.env}-mlflow-artifacts",
        "arn:aws:s3:::${var.project}-${var.env}-mlflow-artifacts/*"
      ]
    }]
  })
}

# Media service IRSA — Cloudinary + S3 media backups
module "media_service_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name = "${var.project}-${var.env}-media-service"

  role_policy_arns = {
    s3 = aws_iam_policy.media_service_s3.arn
  }

  oidc_providers = {
    ex = {
      provider_arn               = var.oidc_provider_arn
      namespace_service_accounts = ["sssm:media-service"]
    }
  }
}

resource "aws_iam_policy" "media_service_s3" {
  name = "${var.project}-${var.env}-media-service-s3"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
      Resource = "arn:aws:s3:::${var.project}-${var.env}-media-backups/*"
    }]
  })
}

# Jenkins CI — ECR push + K8S access
resource "aws_iam_user" "jenkins" {
  name = "${var.project}-${var.env}-jenkins-ci"
  tags = local.common_tags
}

resource "aws_iam_access_key" "jenkins" {
  user = aws_iam_user.jenkins.name
}

resource "aws_iam_user_policy_attachment" "jenkins_ecr" {
  user       = aws_iam_user.jenkins.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"
}

resource "aws_secretsmanager_secret" "jenkins_credentials" {
  name = "${var.project}/${var.env}/jenkins/aws-credentials"
  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "jenkins_credentials" {
  secret_id = aws_secretsmanager_secret.jenkins_credentials.id
  secret_string = jsonencode({
    aws_access_key_id     = aws_iam_access_key.jenkins.id
    aws_secret_access_key = aws_iam_access_key.jenkins.secret
  })
}
