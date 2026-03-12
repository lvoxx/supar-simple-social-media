variable "project"             { type = string }
variable "env"                 { type = string }
variable "vpc_id"              { type = string }
variable "vpc_cidr"            { type = string }
variable "database_subnet_ids" { type = list(string) }
variable "redis_node_type"     { type = string; default = "cache.r6g.large" }
