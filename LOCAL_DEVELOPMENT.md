# Local Development

## Prerequisites

- JDK version pinned by the repository Gradle toolchain;
- Kotlin/Gradle tooling from the checked-in wrapper;
- Android Studio for Android work;
- Xcode and CocoaPods/SPM integration for iOS work;
- Node.js version pinned for web consoles;
- Docker with Compose support;
- Git and pre-commit hooks.

Exact versions are added with the Phase 2 build convention so every contributor uses the same toolchain.

## Intended local workflow

```text
1. copy .env.example to a local, ignored environment file
2. start infrastructure/docker dependencies
3. apply service migrations and seed synthetic data
4. run the gateway and the target service(s)
5. run the relevant app shell
6. run focused unit/contract/integration tests
```

Local services use fake payment, carrier, email, SMS, and push adapters. Real credentials are never needed for local development.

## Configuration rules

- checked-in configuration contains safe defaults only;
- secrets are supplied through environment variables or a local secret manager;
- tenant, locale, currency, and feature-flag context are explicit in test fixtures;
- do not commit `.env` files, generated credentials, build outputs, or provider payloads containing PII.

## Debugging

Every local request should expose request ID and trace ID in logs. Use the local observability profile for traces and metrics when debugging cross-service behavior. Prefer a focused service profile over starting the entire platform.

