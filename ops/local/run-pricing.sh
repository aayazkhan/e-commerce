#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export PRICING_DATABASE_URL="jdbc:postgresql://localhost:5432/pricing"
export PRICING_DATABASE_USERNAME="pricing"
export PRICING_DATABASE_PASSWORD="dummy"
export PRICING_REDIS_URL="redis://localhost:6379"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
export PRICING_PORT="8084"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:pricing-service:run --no-daemon -q
