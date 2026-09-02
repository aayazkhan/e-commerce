#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:?BASE_URL is required}"
base_url="${base_url%/}"
curl_args=(--fail --silent --show-error --max-time "${SMOKE_TIMEOUT_SECONDS:-10}" -H 'Accept: application/json')

check() {
  local path="$1"
  echo "smoke: GET $path"
  curl "${curl_args[@]}" "$base_url$path" >/dev/null
}

check /health/live
check /health/ready
check /metrics
if [[ "${RUN_PUBLIC_SMOKE:-false}" == "true" ]]; then
  check /api/v1/catalog/products?limit=1
  check /api/v1/search?q=smoke\&limit=1
fi
echo "smoke checks passed: $base_url"
