#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export SHIPPING_DATABASE_URL="jdbc:postgresql://localhost:5432/shipping"
export SHIPPING_DATABASE_USERNAME="commerce"
export SHIPPING_DATABASE_PASSWORD="dummy"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
export SHIPPING_PROVIDER_BASE_URL="http://localhost:8096"
export SHIPPING_PROVIDER_API_KEY="dev-stub-key"
export SHIPPING_PROVIDER_WEBHOOK_SECRET="dev-stub-secret"
export SHIPPING_PORT="8093"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:shipping-service:run --no-daemon -q
