#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export CATEGORY_DATABASE_URL="jdbc:postgresql://localhost:5432/category"
export CATEGORY_DATABASE_USERNAME="category"
export CATEGORY_DATABASE_PASSWORD="dummy"
export CATEGORY_REDIS_URL="redis://localhost:6379"
export JWT_ACTIVE_KEY_ID="v1"
export JWT_KEYS="v1=dev-secret-key-change-me"
export IDENTITY_TENANT_ID="tenant-1"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export CATEGORY_PORT="8082"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:category-service:run --no-daemon -q
