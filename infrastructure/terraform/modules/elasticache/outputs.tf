output "redis_configuration_endpoint" { value = module.elasticache_redis.configuration_endpoint_address }
output "redis_auth_secret_arn"        { value = aws_secretsmanager_secret.redis_auth.arn }
