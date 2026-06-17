variable "aws_region" {
  description = "AWS region for all regional resources."
  type        = string
  default     = "ap-southeast-1"
}

variable "environment" {
  description = "Deployment environment (drives resource names/tags)."
  type        = string
  default     = "staging"
}

variable "az_count" {
  description = "Number of AZs to span (>=2 so the RDS subnet group is valid)."
  type        = number
  default     = 2
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "enable_interface_endpoints" {
  description = "Create interface VPC endpoints (ECR/STS/Logs). Cuts NAT data cost; each endpoint ~$7/mo."
  type        = bool
  default     = true
}

# --- EKS -------------------------------------------------------------------
variable "kubernetes_version" {
  description = "EKS control-plane Kubernetes version."
  type        = string
  default     = "1.33"
}

variable "node_instance_types" {
  description = "Instance types for the default managed node group (ARCHITECTURE.md: 3x t3.large)."
  type        = list(string)
  default     = ["t3.large"]
}

variable "node_capacity_type" {
  description = "ON_DEMAND or SPOT. SPOT for cost (architecture: spot/savings)."
  type        = string
  default     = "SPOT"
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_desired_size" {
  type    = number
  default = 3
}

variable "node_max_size" {
  type    = number
  default = 4
}

# --- RDS -------------------------------------------------------------------
variable "rds_instance_class" {
  description = "RDS instance class (Graviton t4g for cost)."
  type        = string
  default     = "db.t4g.small"
}

variable "rds_engine_version" {
  description = "Postgres engine version (matches local Postgres 17)."
  type        = string
  default     = "17.4"
}

variable "rds_allocated_storage" {
  type    = number
  default = 20
}

variable "rds_max_allocated_storage" {
  description = "Upper bound for storage autoscaling."
  type        = number
  default     = 100
}

variable "db_name" {
  type    = string
  default = "sssm"
}

variable "db_username" {
  type    = string
  default = "sssm_app"
}

# --- Cloudflare ------------------------------------------------------------
variable "cloudflare_api_token" {
  description = "Cloudflare API token (R2 + DNS edit). Set via CLOUDFLARE_API_TOKEN env or tfvars (never commit)."
  type        = string
  default     = null
  sensitive   = true
}

variable "cloudflare_account_id" {
  description = "Cloudflare account ID that owns the R2 buckets."
  type        = string
  default     = ""
}

variable "cloudflare_zone_id" {
  description = "Cloudflare DNS zone ID for the app domain. Empty disables DNS record creation."
  type        = string
  default     = ""
}

variable "domain_name" {
  description = "App hostname managed in Cloudflare (e.g. app.example.com). Empty disables DNS records."
  type        = string
  default     = ""
}

variable "alb_dns_name" {
  description = "Public DNS name of the ingress load balancer (filled after ingress-nginx is up). Empty skips the app CNAME."
  type        = string
  default     = ""
}
