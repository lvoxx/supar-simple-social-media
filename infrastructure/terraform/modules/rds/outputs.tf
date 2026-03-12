output "pg_writer_endpoint" { value = module.aurora_postgresql.cluster_endpoint }
output "pg_reader_endpoint" { value = module.aurora_postgresql.cluster_reader_endpoint }
output "pg_secret_arns"     { value = { for db, s in aws_secretsmanager_secret.db_passwords : db => s.arn } }
