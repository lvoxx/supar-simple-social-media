output "cluster_name"               { value = module.eks.cluster_name }
output "cluster_endpoint"           { value = module.eks.cluster_endpoint }
output "cluster_ca"                 { value = module.eks.cluster_certificate_authority_data }
output "oidc_provider_arn"          { value = module.eks.oidc_provider_arn }
output "node_group_role_arn"        { value = module.eks.eks_managed_node_groups["app"].iam_role_arn }
output "lb_controller_role_arn"     { value = module.lb_controller_irsa.iam_role_arn }
output "cluster_autoscaler_role_arn"{ value = module.cluster_autoscaler_irsa.iam_role_arn }
output "external_secrets_role_arn"  { value = module.external_secrets_irsa.iam_role_arn }
