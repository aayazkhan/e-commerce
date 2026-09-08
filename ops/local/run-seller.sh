#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export SELLER_DATABASE_URL="jdbc:postgresql://localhost:5432/commerce_seller"
export DATABASE_USERNAME="commerce"
export DATABASE_PASSWORD="dummy"
export REDIS_URL="redis://localhost:6379"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export CATALOG_URL="http://localhost:8083"
export INVENTORY_URL="http://localhost:1"
export PROMOTION_URL="http://localhost:1"
export ANALYTICS_URL="http://localhost:1"
export IDENTITY_URL="http://localhost:8081"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
export SELLER_PORT="8100"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:seller-service:run --no-daemon -q
