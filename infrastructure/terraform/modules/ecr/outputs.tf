output "ecr_registry_url" { value = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com" }
output "ecr_repos"        { value = { for k, v in aws_ecr_repository.services : k => v.repository_url } }
