locals {
  common_tags = {
    Project     = var.project
    Environment = var.env
    ManagedBy   = "terraform"
  }

  buckets = {
    "terraform-state"  = { versioning = true,  lifecycle_days = 0    }
    "mlflow-artifacts" = { versioning = true,  lifecycle_days = 0    }
    "media-backups"    = { versioning = true,  lifecycle_days = 90   }
    "logs"             = { versioning = false, lifecycle_days = 30   }
  }
}

resource "aws_s3_bucket" "buckets" {
  for_each = local.buckets
  bucket   = "${var.project}-${var.env}-${each.key}"
  tags     = local.common_tags
}

resource "aws_s3_bucket_versioning" "buckets" {
  for_each = { for k, v in local.buckets : k => v if v.versioning }
  bucket   = aws_s3_bucket.buckets[each.key].id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "buckets" {
  for_each = local.buckets
  bucket   = aws_s3_bucket.buckets[each.key].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "buckets" {
  for_each = local.buckets
  bucket   = aws_s3_bucket.buckets[each.key].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.buckets["logs"].id
  rule {
    id     = "expire-logs"
    status = "Enabled"
    expiration { days = local.buckets["logs"].lifecycle_days }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "media_backups" {
  bucket = aws_s3_bucket.buckets["media-backups"].id
  rule {
    id     = "transition-to-glacier"
    status = "Enabled"
    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
    transition {
      days          = local.buckets["media-backups"].lifecycle_days
      storage_class = "GLACIER"
    }
  }
}
