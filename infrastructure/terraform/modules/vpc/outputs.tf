output "vpc_id"              { value = module.vpc.vpc_id }
output "public_subnet_ids"  { value = module.vpc.public_subnets }
output "private_subnet_ids" { value = module.vpc.private_subnets }
output "database_subnet_ids"{ value = module.vpc.database_subnets }
output "vpc_cidr_block"     { value = module.vpc.vpc_cidr_block }
