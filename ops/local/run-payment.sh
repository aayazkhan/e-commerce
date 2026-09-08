#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export PAYMENT_DATABASE_URL="jdbc:postgresql://localhost:5432/payment"
export PAYMENT_DATABASE_USERNAME="commerce"
export PAYMENT_DATABASE_PASSWORD="dummy"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
# Only required to satisfy boot-time config loading -- the COD payment path (what checkout uses
# locally) never actually calls out to this URL, so a placeholder is fine.
export PAYMENT_PROVIDER_BASE_URL="http://localhost:1"
export PAYMENT_PROVIDER_API_KEY="unused"
export PAYMENT_PROVIDER_WEBHOOK_SECRET="unused"
export PAYMENT_PORT="8092"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:payment-service:run --no-daemon -q
