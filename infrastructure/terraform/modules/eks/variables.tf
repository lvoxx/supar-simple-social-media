variable "project"             { type = string }
variable "env"                 { type = string }
variable "eks_cluster_version" { type = string; default = "1.30" }
variable "vpc_id"              { type = string }
variable "private_subnet_ids"  { type = list(string) }
