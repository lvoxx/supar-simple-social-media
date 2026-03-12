variable "project"    { type = string }
variable "env"        { type = string }
variable "aws_region" { type = string }

variable "availability_zones" { type = list(string) }
variable "vpc_cidr"           { type = string }
variable "public_subnets"     { type = list(string) }
variable "private_subnets"    { type = list(string) }
variable "database_subnets"   { type = list(string) }

variable "eks_cluster_version"      { type = string }
variable "rds_instance_class"       { type = string }
variable "redis_node_type"          { type = string }
variable "kafka_instance_type"      { type = string }
variable "kafka_storage_gb"         { type = number }
variable "opensearch_instance_type" { type = string }
variable "opensearch_volume_gb"     { type = number }
variable "domain_name"              { type = string }
