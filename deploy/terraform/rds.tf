# Postgres 17 — single-AZ source of truth (ARCHITECTURE.md: start single-AZ to
# avoid the Multi-AZ cost doubling). Master password is generated and stored in
# AWS Secrets Manager (manage_master_user_password), never in Terraform state.
resource "aws_security_group" "rds" {
  name        = "${local.name}-rds"
  description = "Postgres access from the EKS node group only"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description     = "Postgres from EKS nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  tags = local.tags
}

module "rds" {
  source  = "terraform-aws-modules/rds/aws"
  version = "~> 7.0"

  identifier = "${local.name}-pg"

  engine               = "postgres"
  engine_version       = var.rds_engine_version
  family               = "postgres17"
  major_engine_version = "17"
  instance_class       = var.rds_instance_class

  allocated_storage     = var.rds_allocated_storage
  max_allocated_storage = var.rds_max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = var.db_username
  port     = 5432

  manage_master_user_password = true  # generated secret -> AWS Secrets Manager
  multi_az                    = false # single-AZ for cost; flip at the Phase 5 hardening gate

  create_db_subnet_group = false
  db_subnet_group_name   = module.vpc.database_subnet_group_name
  vpc_security_group_ids = [aws_security_group.rds.id]

  backup_retention_period      = 7
  deletion_protection          = true
  skip_final_snapshot          = false
  performance_insights_enabled = true # 7-day retention is free tier

  tags = local.tags
}
