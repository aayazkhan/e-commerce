#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export PORT="8080"
export APP_ENV="local"
export IDENTITY_SERVICE_URL="http://localhost:8081"
export CATALOG_SERVICE_URL="http://localhost:8083"
export SELLER_SERVICE_URL="http://localhost:8100"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:api-gateway:run --no-daemon -q
