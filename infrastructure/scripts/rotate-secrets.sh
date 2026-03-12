#!/usr/bin/env bash
# rotate-secrets.sh — Rotate a secret in AWS Secrets Manager and restart affected pods
# Usage: ./rotate-secrets.sh <secret-name> [env]
#
# Examples:
#   ./rotate-secrets.sh sssm/prod/user-service
#   ./rotate-secrets.sh sssm/prod/rds/sssm_users prod

set -euo pipefail

SECRET_NAME=${1:?"Usage: $0 <secret-name> [env]"}
ENV=${2:-prod}

echo "Rotating secret: $SECRET_NAME (env: $ENV)"

# ── Determine which K8S service(s) use this secret ────────
# Naming convention: sssm/<env>/<service-name>
SERVICE=$(echo "$SECRET_NAME" | awk -F'/' '{print $3}')

# ── Trigger AWS Secrets Manager rotation ──────────────────
echo "▶ Triggering rotation in AWS Secrets Manager..."
aws secretsmanager rotate-secret \
  --secret-id "$SECRET_NAME" \
  --profile "sssm-$ENV" \
  --region us-east-1

# Wait for rotation to complete
echo "  Waiting for rotation to complete..."
for i in $(seq 1 30); do
  STATUS=$(aws secretsmanager describe-secret \
    --secret-id "$SECRET_NAME" \
    --profile "sssm-$ENV" \
    --region us-east-1 \
    --query 'RotationRules' \
    --output text 2>/dev/null || echo "done")
  
  LAST_ROTATION=$(aws secretsmanager describe-secret \
    --secret-id "$SECRET_NAME" \
    --profile "sssm-$ENV" \
    --region us-east-1 \
    --query 'LastRotatedDate' \
    --output text 2>/dev/null || echo "None")

  if [ "$LAST_ROTATION" != "None" ]; then
    echo "  ✓ Rotation completed at: $LAST_ROTATION"
    break
  fi
  sleep 5
done

# ── Force ExternalSecret refresh ─────────────────────────
echo "▶ Triggering ExternalSecret refresh in K8S..."
if [ -n "$SERVICE" ]; then
  kubectl annotate externalsecret "${SERVICE}-secrets" \
    -n sssm \
    force-sync="$(date +%s)" \
    --overwrite 2>/dev/null || echo "  (ExternalSecret not found, skipping)"
fi

# Wait for K8S secret to update
sleep 10

# ── Rolling restart of affected deployment ─────────────────
echo "▶ Rolling restart of deployment: $SERVICE"
if kubectl get deployment "$SERVICE" -n sssm &>/dev/null; then
  kubectl rollout restart deployment/"$SERVICE" -n sssm
  kubectl rollout status deployment/"$SERVICE" -n sssm --timeout=300s
  echo "  ✓ Deployment $SERVICE restarted successfully"
else
  echo "  (Deployment $SERVICE not found in namespace sssm, skipping restart)"
fi

echo ""
echo "✓ Secret rotation complete for: $SECRET_NAME"
