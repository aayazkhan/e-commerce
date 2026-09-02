# Phase 10 test strategy

Phase 10 uses behavior-first tests and keeps commerce-critical transactions independent from downstream notification, recommendation, and analytics consumers. Tests must assert business outcomes and invariants; executing a method without an assertion is not coverage.

## Gradle test lanes

| Lane | Command | Scope | Current state |
| --- | --- | --- | --- |
| Fast/unit | `./gradlew test --no-daemon` | All default JUnit tests; integration/e2e tags excluded | PASS locally |
| Coverage report | `./gradlew jacocoTestReport --no-daemon` | Aggregate HTML, XML, CSV over every Kotlin JVM production class | PASS locally |
| Coverage gate | `./gradlew jacocoTestCoverageVerification --no-daemon` | 100% aggregate line and branch coverage; no exclusions | FAILS honestly at current baseline |
| Integration | `./gradlew integrationTest -PrunIntegration=true --no-daemon` | Tests tagged `integration`; requires Docker/Testcontainers and opt-in environment variables | Not run locally |
| E2E | `./gradlew e2eTest -PrunE2e=true -PincludeE2e=true --no-daemon` | Tests tagged `e2e` against the disposable commerce environment | No e2e tests are registered yet |
| API contracts | `./tests/contracts/validate-contracts.sh` | OpenAPI parsing and service/Dockerfile release map | PASS locally: 24 contracts |
| Load | `k6 run tests/load/k6/ecommerce-critical.js` | Realistic browse/search/cart/checkout traffic | Not run locally |
| Chaos | See `tests/chaos/README.md` | Controlled failure and recovery against a deployed environment | Not run locally |

The root `integrationTest` and `e2eTest` tasks are guarded so an accidental local command cannot start containers or hit provider sandboxes. A skipped lane is not a passing lane.

## Test pyramid and required assertions

1. Unit tests cover value objects, state transitions, validators, calculations, authorization, retry/backoff, event envelopes, and idempotency decisions.
2. Integration tests use real PostgreSQL, Kafka, Redis, and OpenSearch-compatible dependencies where the behavior depends on transaction, offset, TTL, index, or locking semantics. Testcontainers is the default disposable infrastructure.
3. Contract tests validate API/event/provider schemas and signed webhook behavior at each boundary.
4. Functional tests exercise cross-service workflows and compensation outcomes.
5. E2E tests exercise only critical user journeys against the reproducible environment described in `tests/e2e/README.md`.

Every scenario should cover the applicable happy path, empty/missing input, boundary, invalid input, duplicate, unauthorized/forbidden, conflict, timeout, dependency error, retry, and permanent-failure outcomes.

## Coverage policy

- JaCoCo is applied to every Kotlin/JVM module in `settings.gradle.kts`.
- Reports are written to `build/reports/jacoco/aggregate/{html,jacoco.xml,jacoco.csv}` and per-module `build/reports/jacoco/test` directories.
- No production package, controller, repository, security class, error path, or serializer is excluded.
- Both module verification tasks and the aggregate root gate require `LINE >= 1.0` and `BRANCH >= 1.0`.
- The CI workflow runs the gate after publishing reports. The current red result is intentional evidence that Phase 10 is not complete.

## Critical invariants

The permanent regression suite must encode these as explicit assertions rather than infer them from coverage:

- inventory never becomes negative and concurrent reservations cannot oversell;
- an idempotency key maps to exactly one order/payment/refund operation;
- a reused key with a different request is a conflict;
- total refunded amount never exceeds captured amount;
- seller and user ownership checks reject cross-tenant/resource access;
- ledger entries are append-only and payouts cannot be duplicated;
- duplicate Kafka delivery cannot duplicate projections or notifications;
- notification/provider/analytics failures cannot block checkout/order creation;
- analytics persistence hashes or removes user identifiers and never stores payment secrets;
- retry loops terminate in a DLQ and circuit breakers stop calls to unhealthy dependencies.

## Evidence rules

Only measured values go in `final-test-report.md`. Infrastructure-dependent results are recorded as `NOT RUN` with the required environment and command. A coverage report is evidence of executed bytecode, not evidence that a business scenario is meaningful; the review gate therefore also requires the test matrix, invariant assertions, contract evidence, and failure/recovery evidence.
