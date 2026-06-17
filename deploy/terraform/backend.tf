# Remote state backend (S3 with native lockfile-based locking).
#
# Partial configuration: bucket/key/region are supplied at init time so the same
# root module can back multiple environments. Nothing is applied here yet (this
# repo is authored locally with no cloud account) — to wire it up later:
#
#   terraform init \
#     -backend-config="bucket=sssm-tfstate-<acct>" \
#     -backend-config="key=staging/infra.tfstate" \
#     -backend-config="region=ap-southeast-1"
#
# For purely-local experiments: `terraform init -backend=false` (local state).
terraform {
  backend "s3" {
    encrypt      = true
    use_lockfile = true # S3-native state locking (Terraform >= 1.10); no DynamoDB table needed
  }
}
