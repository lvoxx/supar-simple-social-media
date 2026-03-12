#!/usr/bin/env bash
# bootstrap.sh — Full infrastructure provisioning from scratch
# Usage: ./bootstrap.sh <env>  (env = dev | staging | prod)

set -euo pipefail

ENV=${1:-prod}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"

echo "======================================================"
echo " SSSM Bootstrap — environment: $ENV"
echo "======================================================"

# ── Step 1: Terraform ─────────────────────────────────────
echo ""
echo "▶ Step [1/4] — Terraform: provisioning AWS infrastructure"
cd "$INFRA_DIR/terraform/environments/$ENV"

terraform init
terraform plan -var-file=terraform.tfvars -out=tfplan
terraform apply tfplan

echo "  ✓ Terraform apply complete"

# Export outputs for subsequent steps
terraform output -json > /tmp/tf-outputs-$ENV.json
echo "  ✓ Outputs written to /tmp/tf-outputs-$ENV.json"

# ── Step 2: Kubeconfig ────────────────────────────────────
echo ""
echo "▶ Step [2/4] — Configuring kubectl"

CLUSTER_NAME=$(jq -r '.cluster_name.value' /tmp/tf-outputs-$ENV.json 2>/dev/null || echo "sssm-$ENV")
AWS_PROFILE="sssm-$ENV"

aws eks update-kubeconfig \
  --region us-east-1 \
  --name "$CLUSTER_NAME" \
  --profile "$AWS_PROFILE"

echo "  ✓ kubeconfig updated for cluster: $CLUSTER_NAME"

# ── Step 3: Ansible — node hardening ──────────────────────
echo ""
echo "▶ Step [3/4] — Ansible: hardening EKS nodes"
cd "$INFRA_DIR/ansible"

# Generate dynamic inventory from Terraform outputs
python3 inventories/$ENV/ec2_inventory.py \
  --tf-outputs /tmp/tf-outputs-$ENV.json 2>/dev/null || true

ansible-playbook \
  -i "inventories/$ENV/" \
  node-hardening.yaml \
  --extra-vars "env=$ENV" \
  --timeout 120

echo "  ✓ Node hardening complete"

# ── Step 4: ArgoCD bootstrap ──────────────────────────────
echo ""
echo "▶ Step [4/4] — ArgoCD: bootstrapping GitOps"
cd "$INFRA_DIR/ansible"

ansible-playbook \
  -i "inventories/$ENV/" \
  cluster-bootstrap.yaml \
  --extra-vars "env=$ENV"

echo ""
echo "======================================================"
echo " Bootstrap complete!"
echo " ArgoCD will now sync all applications automatically."
echo ""
echo " Access ArgoCD UI:"
echo "   kubectl port-forward svc/argocd-server -n argocd 8080:443"
echo "======================================================"
