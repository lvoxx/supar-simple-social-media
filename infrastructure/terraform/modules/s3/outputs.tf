output "bucket_arns" { value = { for k, v in aws_s3_bucket.buckets : k => v.arn } }
output "bucket_ids"  { value = { for k, v in aws_s3_bucket.buckets : k => v.id } }
