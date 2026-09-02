#!/usr/bin/env bash
set -euo pipefail

environment="${1:-${DEPLOY_ENVIRONMENT:-}}"
deployment="${2:-${DEPLOYMENT_NAME:-}}"
namespace="${KUBE_NAMESPACE:-commerce}"
[[ "$environment" == "staging" || "$environment" == "production" ]] || { echo "rollback requires staging or production" >&2; exit 2; }
[[ -n "$deployment" && "$deployment" != "*" ]] || { echo "provide one DEPLOYMENT_NAME; broad rollback is forbidden" >&2; exit 2; }
command -v kubectl >/dev/null || { echo "kubectl is required" >&2; exit 1; }
kubectl rollout undo "deployment/$deployment" --namespace "$namespace"
kubectl rollout status "deployment/$deployment" --namespace "$namespace" --timeout="${ROLLOUT_TIMEOUT:-10m}"
echo "application rollback completed: environment=$environment deployment=$deployment"
