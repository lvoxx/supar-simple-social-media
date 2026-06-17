data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "sssm-${var.environment}"

  # First N AZs in the region — single-AZ RDS still needs a subnet group spanning >=2 AZs.
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  tags = {
    Project     = "sssm"
    Environment = var.environment
    ManagedBy   = "terraform"
    Repo        = "supar-simple-social-media"
  }
}
