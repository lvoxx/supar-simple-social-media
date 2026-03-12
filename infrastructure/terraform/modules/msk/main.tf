locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }
}

resource "aws_security_group" "msk" {
  name        = "${var.project}-${var.env}-msk"
  description = "MSK Kafka broker access"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 9096
    to_port     = 9096
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
    description = "Kafka SASL/SCRAM TLS from VPC"
  }

  ingress {
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
    description = "Kafka plaintext from VPC"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.common_tags
}

resource "aws_kms_key" "msk" {
  description             = "${var.project}-${var.env} MSK encryption"
  deletion_window_in_days = 10
  tags                    = local.common_tags
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${var.project}-${var.env}"
  retention_in_days = 30
  tags              = local.common_tags
}

resource "aws_msk_configuration" "this" {
  name           = "${var.project}-${var.env}-kafka-config"
  kafka_versions = ["3.7.x"]

  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=12
    log.retention.hours=168
    log.segment.bytes=1073741824
    log.retention.check.interval.ms=300000
    compression.type=lz4
    message.max.bytes=10485760
  EOT
}

resource "random_password" "msk_password" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "msk_credentials" {
  name                    = "AmazonMSK_${var.project}-${var.env}-credentials"
  kms_key_id              = aws_kms_key.msk.arn
  tags                    = local.common_tags
}

resource "aws_secretsmanager_secret_version" "msk_credentials" {
  secret_id = aws_secretsmanager_secret.msk_credentials.id
  secret_string = jsonencode({
    username = "${var.project}-${var.env}-kafka-user"
    password = random_password.msk_password.result
  })
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.project}-${var.env}-kafka"
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type  = var.kafka_instance_type
    client_subnets = var.database_subnet_ids
    storage_info {
      ebs_storage_info { volume_size = var.kafka_storage_gb }
    }
    security_groups = [aws_security_group.msk.id]
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn
  }

  client_authentication {
    sasl { scram = true }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  open_monitoring {
    prometheus {
      jmx_exporter  { enabled_in_broker = true }
      node_exporter { enabled_in_broker = true }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = local.common_tags
}

resource "aws_msk_scram_secret_association" "this" {
  cluster_arn     = aws_msk_cluster.this.arn
  secret_arn_list = [aws_secretsmanager_secret.msk_credentials.arn]
  depends_on      = [aws_secretsmanager_secret_version.msk_credentials]
}
