variable "project"             { type = string }
variable "env"                 { type = string }
variable "vpc_cidr"            { type = string }
variable "availability_zones"  { type = list(string) }
variable "public_subnets"      { type = list(string) }
variable "private_subnets"     { type = list(string) }
variable "database_subnets"    { type = list(string) }
