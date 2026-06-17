# Media blobs live in Cloudflare R2 (ADR-0001 / ARCHITECTURE.md: zero egress).
# The media-service mints presigned PUT URLs against this bucket.
resource "cloudflare_r2_bucket" "media" {
  account_id    = var.cloudflare_account_id
  name          = "${local.name}-media"
  location      = "apac" # match the AWS region's continent to cut latency
  storage_class = "Standard"
}

# Optional: app hostname -> ingress load balancer, proxied through Cloudflare
# (WAF / DDoS / CDN). Created only when zone + domain + ALB name are supplied.
resource "cloudflare_dns_record" "app" {
  count   = var.cloudflare_zone_id != "" && var.domain_name != "" && var.alb_dns_name != "" ? 1 : 0
  zone_id = var.cloudflare_zone_id
  name    = var.domain_name
  type    = "CNAME"
  content = var.alb_dns_name
  ttl     = 1 # 1 = automatic; required when proxied
  proxied = true
}
