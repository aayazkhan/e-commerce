#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export MEDIA_DATABASE_URL="jdbc:postgresql://localhost:5432/media"
export MEDIA_DATABASE_USERNAME="media"
export MEDIA_DATABASE_PASSWORD="dummy"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
# Backblaze B2 (S3-compatible). AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY are the AWS SDK's own
# standard env vars -- B2's Key ID and Application Key map directly onto them, no B2-specific
# credential wiring needed. The actual keys live in ops/local/secrets.env (gitignored).
export MEDIA_S3_BUCKET="eCommerce.khan"
export MEDIA_S3_REGION="eu-central-003"
export MEDIA_S3_ENDPOINT="https://s3.eu-central-003.backblazeb2.com"
# No MEDIA_PUBLIC_BASE_URL: the bucket is Private (avoids Backblaze's billing-verification gate
# on public buckets), so reads always go through media-service's own /view redirect route
# (presigned GET, minted fresh per request) instead of a static public URL -- see Application.kt.
export MEDIA_PORT="8085"
cd /Users/ayyazkhan/e-commerce
[ -f ops/local/secrets.env ] && source ops/local/secrets.env
exec ./gradlew :backend:media-service:run --no-daemon -q
