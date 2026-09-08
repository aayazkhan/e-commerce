#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export IDENTITY_DATABASE_URL="jdbc:postgresql://localhost:5432/identity"
export IDENTITY_DATABASE_USERNAME="identity"
export IDENTITY_DATABASE_PASSWORD="dummy"
export IDENTITY_REDIS_URL="redis://localhost:6379"
export JWT_ACTIVE_KEY_ID="v1"
export JWT_KEYS="v1=dev-secret-key-change-me"
export IDENTITY_TENANT_ID="tenant-1"
export CHALLENGE_DELIVERY_URL="http://localhost:8090/deliver"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export IDENTITY_PORT="8081"
cd /Users/ayyazkhan/e-commerce
exec ./gradlew :backend:identity-service:run --no-daemon -q
