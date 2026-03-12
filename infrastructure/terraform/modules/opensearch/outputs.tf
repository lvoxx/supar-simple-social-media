output "opensearch_endpoint"   { value = "https://${aws_opensearch_domain.this.endpoint}" }
output "opensearch_secret_arn" { value = aws_secretsmanager_secret.opensearch_credentials.arn }
output "opensearch_domain_arn" { value = aws_opensearch_domain.this.arn }
