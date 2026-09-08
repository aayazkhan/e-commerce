#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export CHECKOUT_DATABASE_URL="jdbc:postgresql://localhost:5432/checkout"
export CHECKOUT_DATABASE_USERNAME="commerce"
export CHECKOUT_DATABASE_PASSWORD="dummy"
export JWT_KEYS="v1=dev-secret-key-change-me"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_TENANT_ID="tenant-1"
export CART_SERVICE_URL="http://localhost:8088"
export PRICING_SERVICE_URL="http://localhost:8084"
export PROMOTION_SERVICE_URL="http://localhost:8090"
export INVENTORY_SERVICE_URL="http://localhost:8087"
export IDENTITY_SERVICE_URL="http://localhost:8081"
export CATALOG_SERVICE_URL="http://localhost:8083"
export SHIPPING_SERVICE_URL="http://localhost:8093"
export ORDER_SERVICE_URL="http://localhost:8091"
export PAYMENT_SERVICE_URL="http://localhost:8092"
export DEFAULT_WAREHOUSE_ID="wh_f95edf1e-db77-418c-89a7-75e987348a92"
export INTERNAL_SERVICE_TOKEN="dev-internal-token"
export CHECKOUT_PORT="8095"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:checkout-service:run --no-daemon -q
