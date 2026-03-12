locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.project}-${var.env}-redis"
  subnet_ids = var.database_subnet_ids
  tags       = local.common_tags
}

resource "aws_security_group" "redis" {
  name        = "${var.project}-${var.env}-redis"
  description = "ElastiCache Redis access"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
    description = "Redis from VPC"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.common_tags
}

resource "random_password" "redis_auth" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "redis_auth" {
  name = "${var.project}/${var.env}/redis/auth"
  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "redis_auth" {
  secret_id     = aws_secretsmanager_secret.redis_auth.id
  secret_string = jsonencode({ auth_token = random_password.redis_auth.result })
}

resource "aws_cloudwatch_log_group" "redis" {
  name              = "/aws/elasticache/${var.project}-${var.env}"
  retention_in_days = 30
  tags              = local.common_tags
}

module "elasticache_redis" {
  source  = "terraform-aws-modules/elasticache/aws"
  version = "~> 1.2"

  cluster_id    = "${var.project}-${var.env}-redis"
  description   = "SSSM Platform Redis"
  engine_version = "7.1"
  node_type     = var.redis_node_type

  cluster_mode_enabled    = true
  num_node_groups         = 3
  replicas_per_node_group = var.env == "prod" ? 2 : 1

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.redis_auth.result
  auth_token_update_strategy = "ROTATE"

  apply_immediately          = var.env != "prod"
  automatic_failover_enabled = true
  multi_az_enabled           = var.env == "prod"

  maintenance_window       = "sun:05:00-sun:06:00"
  snapshot_retention_limit = var.env == "prod" ? 7 : 1
  snapshot_window          = "04:00-05:00"

  log_delivery_configuration = [{
    destination      = aws_cloudwatch_log_group.redis.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }]

  tags = local.common_tags
}
