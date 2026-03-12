locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }

  databases = [
    "sssm_users",
    "sssm_media",
    "sssm_posts",
    "sssm_groups",
    "sssm_post_guard",
    "sssm_user_analysis",
    "sssm_ai_dashboard",
  ]
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.project}-${var.env}-rds"
  subnet_ids = var.database_subnet_ids
  tags       = local.common_tags
}

resource "aws_security_group" "rds" {
  name        = "${var.project}-${var.env}-rds"
  description = "RDS Aurora PostgreSQL access"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
    description = "PostgreSQL from VPC"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.common_tags
}

resource "aws_iam_role" "rds_monitoring" {
  name = "${var.project}-${var.env}-rds-monitoring"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
    }]
  })
  managed_policy_arns = ["arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"]
  tags                = local.common_tags
}

module "aurora_postgresql" {
  source  = "terraform-aws-modules/rds-aurora/aws"
  version = "~> 9.3"

  name           = "${var.project}-${var.env}-pg"
  engine         = "aurora-postgresql"
  engine_version = "16.2"
  instance_class = var.rds_instance_class

  vpc_id               = var.vpc_id
  db_subnet_group_name = aws_db_subnet_group.this.name
  security_group_ids   = [aws_security_group.rds.id]

  instances = {
    writer   = {}
    reader-1 = { promotion_tier = 1 }
    reader-2 = { promotion_tier = 2, availability_zone = var.availability_zones[2] }
  }

  storage_encrypted   = true
  deletion_protection = var.env == "prod"

  backup_retention_period      = var.env == "prod" ? 30 : 7
  preferred_backup_window      = "03:00-04:00"
  preferred_maintenance_window = "sun:04:00-sun:05:00"

  enabled_cloudwatch_logs_exports = ["postgresql"]
  monitoring_interval             = 60
  monitoring_role_arn             = aws_iam_role.rds_monitoring.arn

  manage_master_user_password = true

  tags = local.common_tags
}

resource "random_password" "db_passwords" {
  for_each = toset(local.databases)
  length   = 32
  special  = false
}

resource "aws_secretsmanager_secret" "db_passwords" {
  for_each = toset(local.databases)
  name     = "${var.project}/${var.env}/rds/${each.key}"
  tags     = local.common_tags
}

resource "aws_secretsmanager_secret_version" "db_passwords" {
  for_each  = toset(local.databases)
  secret_id = aws_secretsmanager_secret.db_passwords[each.key].id
  secret_string = jsonencode({
    username = "${each.key}_user"
    password = random_password.db_passwords[each.key].result
    host     = module.aurora_postgresql.cluster_endpoint
    port     = 5432
    dbname   = each.key
  })
}

resource "null_resource" "init_databases" {
  for_each = toset(local.databases)

  provisioner "local-exec" {
    command = <<-EOT
      PGPASSWORD=$(aws secretsmanager get-secret-value \
        --secret-id ${module.aurora_postgresql.cluster_master_user_secret[0].secret_arn} \
        --query SecretString --output text | jq -r .password) \
      psql -h ${module.aurora_postgresql.cluster_endpoint} \
           -U ${module.aurora_postgresql.cluster_master_username} \
           -c "CREATE DATABASE IF NOT EXISTS ${each.key};" \
           -c "CREATE USER IF NOT EXISTS ${each.key}_user WITH ENCRYPTED PASSWORD '${random_password.db_passwords[each.key].result}';" \
           -c "GRANT ALL PRIVILEGES ON DATABASE ${each.key} TO ${each.key}_user;" \
           postgres
    EOT
  }

  depends_on = [module.aurora_postgresql]
}
