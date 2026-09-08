#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export CART_DATABASE_URL="jdbc:postgresql://localhost:5432/cart"
export CART_DATABASE_USERNAME="commerce"
export CART_DATABASE_PASSWORD="dummy"
export CART_REDIS_URL="redis://localhost:6379"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
export PRICING_BASE_URL="http://localhost:8084"
export INVENTORY_BASE_URL="http://localhost:8087"
export CART_PORT="8088"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:cart-service:run --no-daemon -q
