# ADR-0001: Use Cloudflare R2 + Cloudflare CDN for media

- Status: Accepted
- Date: 2026-06-15

## Context

SSSM is media-heavy (image + video posts) and must run within a $1–3k/month budget. On a media
platform, **egress bandwidth is the dominant variable cost**. AWS S3 + CloudFront charge
~$0.085/GB egress; at modest video volume this alone can exceed the entire compute budget.

The project's `docker/.dev.env` originally assumed S3, and the user's stated preference is
AWS-first — but the user explicitly allowed alternatives "if they are more cost-effective for
certain components."

## Decision

Use **Cloudflare R2** for media object storage and **Cloudflare** for CDN/WAF/cache. R2 has
**zero egress fees**. Origin media flows R2 → Cloudflare → client at no per-GB egress cost.

- Images transformed on the fly by self-hosted `imgproxy` (AVIF/WebP).
- Video transcoded to HLS by `ffmpeg` on Vast.ai GPU bursts / spot EC2, stored in R2.
- The rest of the platform remains AWS-first (EKS, RDS, ALB).

## Consequences

- Largest single cost saving in the budget; protects against runaway egress under viral load.
- Two clouds to operate (AWS + Cloudflare); credentials and IaC span both providers.
- R2 is S3-API-compatible, so the `media-service` storage client stays S3-SDK-based — low
  switching cost if we later move back to S3.
- Local dev keeps LocalStack/MinIO behind the same S3 API.

## Alternatives considered

- **S3 + CloudFront (pure AWS)**: simplest IAM/integration, but egress risks the budget.
- **S3 + Cloudflare**: AWS-native storage, mitigated egress, but pays S3 egress to Cloudflare
  unless using a private interconnect; more complex than R2 for no real saving.
