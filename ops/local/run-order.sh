#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export ORDER_DATABASE_URL="jdbc:postgresql://localhost:5432/order"
export ORDER_DATABASE_USERNAME="commerce"
export ORDER_DATABASE_PASSWORD="dummy"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
export ORDER_PORT="8091"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:order-service:run --no-daemon -q
