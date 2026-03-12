locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }
}

resource "aws_security_group" "opensearch" {
  name        = "${var.project}-${var.env}-opensearch"
  description = "OpenSearch access"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
    description = "HTTPS from VPC"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.common_tags
}

resource "aws_cloudwatch_log_group" "opensearch" {
  name              = "/aws/opensearch/${var.project}-${var.env}"
  retention_in_days = 30
  tags              = local.common_tags
}

resource "aws_cloudwatch_log_resource_policy" "opensearch" {
  policy_name = "${var.project}-${var.env}-opensearch-logs"
  policy_document = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "es.amazonaws.com" }
      Action    = ["logs:PutLogEvents", "logs:CreateLogStream"]
      Resource  = "${aws_cloudwatch_log_group.opensearch.arn}:*"
    }]
  })
}

module "opensearch_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name = "${var.project}-${var.env}-opensearch"

  role_policy_arns = {
    opensearch = aws_iam_policy.opensearch_access.arn
  }

  oidc_providers = {
    ex = {
      provider_arn               = var.oidc_provider_arn
      namespace_service_accounts = ["sssm:search-service"]
    }
  }
}

resource "aws_iam_policy" "opensearch_access" {
  name = "${var.project}-${var.env}-opensearch-access"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["es:ESHttp*"]
      Resource = "arn:aws:es:*:*:domain/${var.project}-${var.env}/*"
    }]
  })
}

resource "aws_opensearch_domain" "this" {
  domain_name    = "${var.project}-${var.env}"
  engine_version = "OpenSearch_2.13"

  cluster_config {
    instance_type            = var.opensearch_instance_type
    instance_count           = var.env == "prod" ? 3 : 1
    dedicated_master_enabled = var.env == "prod"
    dedicated_master_type    = "r6g.large.search"
    dedicated_master_count   = 3
    zone_awareness_enabled   = var.env == "prod"
    dynamic "zone_awareness_config" {
      for_each = var.env == "prod" ? [1] : []
      content {
        availability_zone_count = 3
      }
    }
  }

  ebs_options {
    ebs_enabled = true
    volume_type = "gp3"
    volume_size = var.opensearch_volume_gb
    iops        = 3000
    throughput  = 125
  }

  encrypt_at_rest         { enabled = true }
  node_to_node_encryption { enabled = true }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

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

  depends_on = [aws_cloudwatch_log_resource_policy.opensearch]
  tags       = local.common_tags
}

resource "aws_secretsmanager_secret" "opensearch_credentials" {
  name = "${var.project}/${var.env}/opensearch/credentials"
  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "opensearch_credentials" {
  secret_id = aws_secretsmanager_secret.opensearch_credentials.id
  secret_string = jsonencode({
    endpoint = "https://${aws_opensearch_domain.this.endpoint}"
  })
}
