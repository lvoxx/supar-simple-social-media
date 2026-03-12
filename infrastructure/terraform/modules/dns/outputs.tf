output "zone_id"          { value = aws_route53_zone.primary.zone_id }
output "certificate_arn"  { value = aws_acm_certificate_validation.wildcard.certificate_arn }
output "name_servers"     { value = aws_route53_zone.primary.name_servers }
