# Terraform + provider version constraints.
# Pins follow "latest stable" as of the SSSM Phase-1 IaC slice (see deploy/terraform/README.md).
terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0" # 6.50.x current; required by vpc>=6, eks>=21, rds>=7
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0" # 5.20.x current; v5 renamed cloudflare_record -> cloudflare_dns_record
    }
  }
}
