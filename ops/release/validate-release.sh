#!/usr/bin/env bash
set -euo pipefail

environment="${1:-${DEPLOY_ENVIRONMENT:-}}"
image_tag="${IMAGE_TAG:-${GITHUB_SHA:-}}"

if [[ -z "$environment" || -z "$image_tag" ]]; then
  echo "usage: DEPLOY_ENVIRONMENT=staging IMAGE_TAG=<immutable-commit> $0" >&2
  exit 2
fi
case "$environment" in
  local|development|test|staging|production) ;;
  *) echo "unsupported environment: $environment" >&2; exit 2 ;;
esac
if [[ "$image_tag" == "latest" || "$image_tag" == *REPLACE* || "$image_tag" == *SNAPSHOT* ]]; then
  echo "mutable or placeholder image tag rejected: $image_tag" >&2
  exit 1
fi
if [[ ! "$image_tag" =~ ^[A-Za-z0-9._-]{7,128}$ ]]; then
  echo "image tag is not a valid immutable release identifier: $image_tag" >&2
  exit 1
fi

overlay="deploy/k8s/phase9/overlays/$environment"
[[ -d "$overlay" ]] || { echo "missing overlay: $overlay" >&2; exit 1; }
command -v kubectl >/dev/null || { echo "kubectl is required to render deployment manifests" >&2; exit 1; }

temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT
cp -R deploy/k8s/phase9 "$temporary_root/phase9"
overlay_copy="$temporary_root/phase9/overlays/$environment"
if [[ "$environment" == "staging" || "$environment" == "production" ]]; then
  if [[ "$environment" == "production" ]]; then
    digest_file="${IMAGE_DIGESTS_FILE:-}"
    if [[ "${REQUIRE_DIGESTS:-false}" == "true" && -z "$digest_file" ]]; then
      echo "production deployment requires IMAGE_DIGESTS_FILE" >&2
      exit 1
    fi
    if [[ -n "$digest_file" ]]; then
      [[ -f "$digest_file" ]] || { echo "missing image digest evidence: $digest_file" >&2; exit 1; }
      python3 - "$overlay_copy/kustomization.yaml" "$digest_file" <<'PY'
import pathlib
import re
import sys

manifest = pathlib.Path(sys.argv[1])
digest_file = pathlib.Path(sys.argv[2])
text = manifest.read_text()
for raw in digest_file.read_text().splitlines():
    if not raw.strip() or raw.lstrip().startswith('#'):
        continue
    service, reference = raw.split('=', 1)
    digest = reference.rsplit('@', 1)[-1]
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise SystemExit(f'invalid digest for {service}: {digest}')
    pattern = re.compile(rf'(  - name: commerce/{re.escape(service)}\n    newName: [^\n]+\n)    digest: REPLACE_WITH_IMAGE_DIGEST')
    replacement = rf'\1    digest: {digest}'
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f'digest entry missing or duplicated in kustomization: {service}')
manifest.write_text(text)
PY
    elif [[ "${REQUIRE_DIGESTS:-false}" == "true" ]]; then
      echo "production deployment requires digest evidence" >&2
      exit 1
    fi
  else
    if [[ "$(uname)" == "Darwin" ]]; then
      sed -i '' "s/REPLACE_WITH_COMMIT_TAG/$image_tag/g" "$overlay_copy/kustomization.yaml"
    else
      sed -i "s/REPLACE_WITH_COMMIT_TAG/$image_tag/g" "$overlay_copy/kustomization.yaml"
    fi
  fi
fi

rendered="$temporary_root/rendered.yaml"
kubectl kustomize "$overlay_copy" > "$rendered"
if [[ "$environment" == "staging" || "$environment" == "production" ]]; then
  if rg -n '(^|[[:space:]])(latest|REPLACE_WITH_|commerce/[^:[:space:]]+:local)([[:space:]]|$)' "$rendered"; then
    echo "rendered release contains a mutable/local image reference" >&2
    exit 1
  fi
  if rg -n 'example\.invalid|__FROM_EXTERNAL_SECRET_STORE__' "$rendered"; then
    echo "rendered release contains an example endpoint/secret placeholder" >&2
    exit 1
  fi
fi
if ! rg -q 'kind: Deployment' "$rendered"; then
  echo "rendered release contains no Deployments" >&2
  exit 1
fi
cat "$rendered"
