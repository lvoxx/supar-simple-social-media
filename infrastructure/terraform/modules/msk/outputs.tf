output "kafka_bootstrap_brokers_tls" { value = aws_msk_cluster.this.bootstrap_brokers_sasl_scram }
output "kafka_secret_arn"            { value = aws_secretsmanager_secret.msk_credentials.arn }
output "kafka_cluster_arn"           { value = aws_msk_cluster.this.arn }
