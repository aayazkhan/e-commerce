# Testing Strategy

## Test pyramid

```text
                 critical E2E / production smoke
             UI flows and accessibility tests
          service integration and contract tests
       domain/application unit and property tests
```

## Required coverage by layer

| Layer | Focus |
| --- | --- |
| Domain | value objects, pricing, promotion rules, order state machine, conflict policies, property tests |
| Application | command/query orchestration, authorization decisions, idempotent retry behavior |
| Data | repository mapping, migrations, transaction boundaries, outbox/inbox behavior |
| API | OpenAPI schema, auth/error/pagination/idempotency contracts, compatibility checks |
| Service integration | real PostgreSQL/Redis/Kafka/OpenSearch containers where behavior depends on them |
| UI | loading/empty/error/offline/accessibility states and critical interaction paths |
| E2E | browse -> cart -> checkout -> payment callback -> order status; admin/seller high-risk flows |
| Load | catalog/search reads, cart mutation, checkout contention, event lag, provider degradation |
| Security | dependency, SAST, DAST, authz matrix, tenant isolation, webhook replay, abuse limits |

Tests must be deterministic, isolated, and safe to run against synthetic data. Contract tests are required before a service is integrated into the gateway. Critical tests run on every merge; heavier load and security suites run on release/nightly schedules.

## Phase 10 commands and evidence

- [Test strategy](docs/phase10/test-strategy.md) — test lanes, coverage policy, and invariants
- [Coverage inventory](docs/phase10/test-coverage-inventory.md) — mechanical module/source/test audit
- [Service test matrix](docs/phase10/test-matrix.md) — current measured coverage and acceptance gaps
- [Final Phase 10 report](docs/phase10/final-test-report.md) — evidence-backed go/no-go status

```bash
./gradlew test jacocoTestReport --no-daemon
./gradlew jacocoTestCoverageVerification --no-daemon
./gradlew integrationTest -PrunIntegration=true --no-daemon
./gradlew e2eTest -PrunE2e=true -PincludeE2e=true --no-daemon
./tests/contracts/validate-contracts.sh
```

The 100% gate intentionally fails until all production line and branch paths are meaningfully tested. Integration and E2E tasks are opt-in and a skipped task is not a passing result.
