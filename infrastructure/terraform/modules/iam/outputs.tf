output "mlflow_role_arn"        { value = module.mlflow_irsa.iam_role_arn }
output "media_service_role_arn" { value = module.media_service_irsa.iam_role_arn }
output "jenkins_secret_arn"     { value = aws_secretsmanager_secret.jenkins_credentials.arn }
