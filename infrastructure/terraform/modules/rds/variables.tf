variable "project"             { type = string }
variable "env"                 { type = string }
variable "vpc_id"              { type = string }
variable "vpc_cidr"            { type = string }
variable "database_subnet_ids" { type = list(string) }
variable "availability_zones"  { type = list(string) }
variable "rds_instance_class"  { type = string; default = "db.r6g.large" }
