#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export INVENTORY_DATABASE_URL="jdbc:postgresql://localhost:5432/inventory"
export INVENTORY_DATABASE_USERNAME="commerce"
export INVENTORY_DATABASE_PASSWORD="dummy"
export INVENTORY_REDIS_URL="redis://localhost:6379"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
export INVENTORY_PORT="8087"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:inventory-service:run --no-daemon -q
