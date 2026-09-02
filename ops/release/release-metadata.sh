#!/usr/bin/env bash
set -euo pipefail

commit="${GIT_COMMIT:-${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}}"
version="${RELEASE_VERSION:-$commit}"
environment="${RELEASE_ENVIRONMENT:-staging}"
image_tag="${IMAGE_TAG:-$commit}"
migration="${MIGRATION_VERSION:-unknown}"
timestamp="${RELEASE_TIMESTAMP:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"

cat <<JSON
{
  "version": "$version",
  "gitCommit": "$commit",
  "imageTag": "$image_tag",
  "environment": "$environment",
  "migrationVersion": "$migration",
  "featureFlagSnapshot": "${FEATURE_FLAG_SNAPSHOT:-record-in-change-ticket}",
  "deploymentTimestampUtc": "$timestamp"
}
JSON
