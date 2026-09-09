#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export IDENTITY_DATABASE_URL="jdbc:postgresql://localhost:5432/identity"
export IDENTITY_DATABASE_USERNAME="identity"
export IDENTITY_DATABASE_PASSWORD="dummy"
export IDENTITY_REDIS_URL="redis://localhost:6379"
export JWT_ACTIVE_KEY_ID="v1"
export JWT_KEYS="v1=dev-secret-key-change-me"
export IDENTITY_TENANT_ID="tenant-1"
export CHALLENGE_DELIVERY_URL="http://localhost:8097/deliver"
# Real transactional email via Resend (https://resend.com) -- email OTPs/verification links go
# through this instead of the local challenge-stub.py above; phone OTPs still use the stub since
# MSG91 isn't wired in yet. "onboarding@resend.dev" is Resend's own always-available test sender.
# The actual API key lives in ops/local/secrets.env (gitignored, never committed) -- see
# ops/local/secrets.env.example for the format.
export RESEND_FROM_ADDRESS="onboarding@resend.dev"
[ -f "$(dirname "${BASH_SOURCE[0]}")/secrets.env" ] && source "$(dirname "${BASH_SOURCE[0]}")/secrets.env"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
export IDENTITY_PORT="8081"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:identity-service:run --no-daemon -q
