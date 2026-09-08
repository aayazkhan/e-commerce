#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export CATALOG_DATABASE_URL="jdbc:postgresql://localhost:5432/catalog"
export CATALOG_DATABASE_USERNAME="catalog"
export CATALOG_DATABASE_PASSWORD="dummy"
export CATALOG_REDIS_URL="redis://localhost:6379"
export JWT_KEYS="v1=dev-secret-key-change-me"
export IDENTITY_TENANT_ID="tenant-1"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
export CATALOG_PORT="8083"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:catalog-service:run --no-daemon -q
