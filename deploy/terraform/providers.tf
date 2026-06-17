provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.tags
  }
}

# Auth via CLOUDFLARE_API_TOKEN env var or -var. Token needs R2 edit, DNS edit,
# and Zone read for the managed zone. Never commit the token.
provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
