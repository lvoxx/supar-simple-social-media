# VPC with public + private (workload) + isolated database subnets.
# Cost discipline (ARCHITECTURE.md): NO NAT Gateway — a single fck-nat NAT
# *instance* provides egress, and VPC endpoints keep ECR/S3 traffic off it.
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"

  name = "${local.name}-vpc"
  cidr = var.vpc_cidr
  azs  = local.azs

  private_subnets  = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i)]
  public_subnets   = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i + 8)]
  database_subnets = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i + 12)]

  create_database_subnet_group = true

  enable_nat_gateway   = false # replaced by the fck-nat instance below
  enable_dns_hostnames = true
  enable_dns_support   = true

  # Subnet tags required by ingress-nginx / AWS LB controller for ELB discovery.
  public_subnet_tags  = { "kubernetes.io/role/elb" = "1" }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }

  tags = local.tags
}

# Cheap NAT *instance* (fck-nat) instead of a managed NAT Gateway, wired into the
# private route tables. Single-AZ to start (cost); flip ha_mode for resilience.
module "fck_nat" {
  source  = "RaJiska/fck-nat/aws"
  version = "~> 1.6"

  name      = "${local.name}-fck-nat"
  vpc_id    = module.vpc.vpc_id
  subnet_id = module.vpc.public_subnets[0]

  ha_mode = false

  update_route_tables = true
  route_tables_ids    = { for idx, id in module.vpc.private_route_table_ids : "private-${idx}" => id }

  tags = local.tags
}

# Security group for interface VPC endpoints: HTTPS from within the VPC only.
resource "aws_security_group" "vpc_endpoints" {
  count       = var.enable_interface_endpoints ? 1 : 0
  name        = "${local.name}-vpce"
  description = "Allow HTTPS from the VPC to interface endpoints"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description = "HTTPS from VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}

module "vpc_endpoints" {
  source  = "terraform-aws-modules/vpc/aws//modules/vpc-endpoints"
  version = "~> 6.0"

  vpc_id             = module.vpc.vpc_id
  security_group_ids = var.enable_interface_endpoints ? [aws_security_group.vpc_endpoints[0].id] : []

  # S3 gateway endpoint is free and keeps image-layer/registry S3 traffic off the NAT instance.
  endpoints = merge(
    {
      s3 = {
        service         = "s3"
        service_type    = "Gateway"
        route_table_ids = module.vpc.private_route_table_ids
        tags            = { Name = "${local.name}-s3" }
      }
    },
    var.enable_interface_endpoints ? {
      ecr_api = { service = "ecr.api", private_dns_enabled = true, subnet_ids = module.vpc.private_subnets }
      ecr_dkr = { service = "ecr.dkr", private_dns_enabled = true, subnet_ids = module.vpc.private_subnets }
      sts     = { service = "sts", private_dns_enabled = true, subnet_ids = module.vpc.private_subnets }
      logs    = { service = "logs", private_dns_enabled = true, subnet_ids = module.vpc.private_subnets }
    } : {}
  )

  tags = local.tags
}
