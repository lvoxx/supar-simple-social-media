variable "project"             { type = string }
variable "env"                 { type = string }
variable "vpc_id"              { type = string }
variable "vpc_cidr"            { type = string }
variable "database_subnet_ids" { type = list(string) }
variable "kafka_instance_type" { type = string; default = "kafka.m5.large" }
variable "kafka_storage_gb"    { type = number; default = 1000 }
