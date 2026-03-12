variable "project"                   { type = string }
variable "env"                       { type = string }
variable "vpc_id"                    { type = string }
variable "vpc_cidr"                  { type = string }
variable "database_subnet_ids"       { type = list(string) }
variable "oidc_provider_arn"         { type = string }
variable "opensearch_instance_type"  { type = string; default = "r6g.large.search" }
variable "opensearch_volume_gb"      { type = number; default = 200 }
