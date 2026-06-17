output "region" {
  description = "AWS region."
  value       = var.aws_region
}

output "vpc_id" {
  value = module.vpc.vpc_id
}

output "private_subnet_ids" {
  value = module.vpc.private_subnets
}

output "nat_instance_eni_id" {
  description = "fck-nat instance ENI."
  value       = module.fck_nat.eni_id
}

output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "configure_kubectl" {
  description = "Populate kubeconfig for the new cluster."
  value       = "aws eks update-kubeconfig --region ${var.aws_region} --name ${module.eks.cluster_name}"
}

output "rds_endpoint" {
  description = "Postgres connection endpoint (host:port)."
  value       = module.rds.db_instance_endpoint
}

output "rds_master_user_secret_arn" {
  description = "Secrets Manager ARN holding the generated DB master password."
  value       = module.rds.db_instance_master_user_secret_arn
}

output "r2_media_bucket" {
  description = "Cloudflare R2 bucket for media."
  value       = cloudflare_r2_bucket.media.name
}
