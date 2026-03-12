locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.8"

  name = "${var.project}-${var.env}"
  cidr = var.vpc_cidr

  azs              = var.availability_zones
  public_subnets   = var.public_subnets
  private_subnets  = var.private_subnets
  database_subnets = var.database_subnets

  enable_nat_gateway     = true
  single_nat_gateway     = var.env == "dev"
  one_nat_gateway_per_az = var.env != "dev"

  enable_dns_hostnames = true
  enable_dns_support   = true

  enable_s3_endpoint             = true
  enable_secretsmanager_endpoint = true
  enable_ecr_api_endpoint        = true
  enable_ecr_dkr_endpoint        = true

  public_subnet_tags = {
    "kubernetes.io/role/elb"                              = "1"
    "kubernetes.io/cluster/${var.project}-${var.env}" = "shared"
  }

  private_subnet_tags = {
    "kubernetes.io/role/internal-elb"                     = "1"
    "kubernetes.io/cluster/${var.project}-${var.env}" = "shared"
  }

  tags = local.common_tags
}
