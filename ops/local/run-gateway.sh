#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export PORT="8080"
export APP_ENV="local"
export IDENTITY_SERVICE_URL="http://localhost:8081"
export CATEGORY_SERVICE_URL="http://localhost:8082"
export CATALOG_SERVICE_URL="http://localhost:8083"
export PRICING_SERVICE_URL="http://localhost:8084"
export INVENTORY_SERVICE_URL="http://localhost:8087"
export CART_SERVICE_URL="http://localhost:8088"
export PROMOTION_SERVICE_URL="http://localhost:8090"
export ORDER_SERVICE_URL="http://localhost:8091"
export PAYMENT_SERVICE_URL="http://localhost:8092"
export SHIPPING_SERVICE_URL="http://localhost:8093"
export CHECKOUT_SERVICE_URL="http://localhost:8095"
export SELLER_SERVICE_URL="http://localhost:8100"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:api-gateway:run --no-daemon -q
