#!/usr/bin/env bash
set -euo pipefail

environment="${1:-${DEPLOY_ENVIRONMENT:-}}"
namespace="${KUBE_NAMESPACE:-commerce}"
[[ -n "$environment" ]] || { echo "environment is required" >&2; exit 2; }
[[ "$environment" != "production" || "${PRODUCTION_APPROVED:-false}" == "true" ]] || { echo "production deployment requires PRODUCTION_APPROVED=true" >&2; exit 1; }
command -v kubectl >/dev/null || { echo "kubectl is required" >&2; exit 1; }

manifest="$(mktemp)"
trap 'rm -f "$manifest"' EXIT
DEPLOY_ENVIRONMENT="$environment" IMAGE_TAG="${IMAGE_TAG:-${GITHUB_SHA:-}}" ops/release/validate-release.sh "$environment" > "$manifest"
kubectl apply -f "$manifest"
kubectl rollout status deployment --all --namespace "$namespace" --timeout="${ROLLOUT_TIMEOUT:-10m}"
echo "deployment applied: environment=$environment namespace=$namespace image=${IMAGE_TAG:-${GITHUB_SHA:-}}"
