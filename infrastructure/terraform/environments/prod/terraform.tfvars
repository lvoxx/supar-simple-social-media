project    = "sssm"
env        = "prod"
aws_region = "us-east-1"

availability_zones = ["us-east-1a", "us-east-1b", "us-east-1c"]
vpc_cidr           = "10.0.0.0/16"
public_subnets     = ["10.0.0.0/20",  "10.0.16.0/20",  "10.0.32.0/20"]
private_subnets    = ["10.0.48.0/20", "10.0.64.0/20",  "10.0.80.0/20"]
database_subnets   = ["10.0.96.0/20", "10.0.112.0/20", "10.0.128.0/20"]

eks_cluster_version = "1.30"

rds_instance_class       = "db.r6g.large"
redis_node_type          = "cache.r6g.large"
kafka_instance_type      = "kafka.m5.large"
kafka_storage_gb         = 1000
opensearch_instance_type = "r6g.large.search"
opensearch_volume_gb     = 200

domain_name = "sssm.com"
