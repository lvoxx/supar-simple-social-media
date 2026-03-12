variable "project"      { type = string }
variable "env"          { type = string }
variable "domain_name"  { type = string }
variable "alb_dns_name" { type = string; default = "placeholder.us-east-1.elb.amazonaws.com" }
