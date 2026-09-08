# Coverage gate: documented coverage exceptions

## Policy: branch coverage

The 100% line/branch coverage gate ([final-test-report.md](final-test-report.md)) holds
without exception for **line coverage**. For **branch coverage**, a module may carry a
documented, exact tolerance in `toleratedMissedBranches` in the root
[build.gradle.kts](../../build.gradle.kts) only when both are true:

1. Real tests have been written for every reachable application code path in that module
   (not just enough to hit a percentage).
2. The remaining missed branches are confirmed, line by line, to be compiler-generated
   dispatch/resume branches from Kotlin `suspend` lambdas — not untested logic.

The tolerance is recorded as an **exact missed-branch count**, not a percentage buffer, so
a regression that adds one real untested branch fails the gate immediately (the count would
need to grow, which nothing does automatically).

## Why this exists

JaCoCo 0.8.13 ships a built-in `KotlinCoroutineFilter` (`org.jacoco.core.internal.analysis.filter`)
that collapses some Kotlin coroutine state-machine bytecode, but it does not collapse every
branch the Kotlin compiler inserts at each `suspend` lambda call site — for example, every Ktor
route handler passed as `get(path) { ... }`, `exception<T> { ... }`, or `route(...) { handle { ... } }`
compiles to a class extending `SuspendLambda`, and the compiler-inserted dispatch/resume check at
that call site is counted by JaCoCo as an uncoverable-by-normal-means branch: reaching the "other"
side requires the coroutine to genuinely suspend and resume at that exact point, which a
same-thread mock-backed unit test does not do.

There is no JaCoCo mechanism to exclude an individual branch — exclusion only works at the
class/method level, and these lambda classes also contain the real handler logic under test, so
excluding the whole class would hide genuine coverage evidence along with the artifact. A bounded,
documented, per-module missed-branch count is the narrowest available fix.

## A second, equally common artifact: `@Serializable` decode paths

Auditing every service for this policy (see "Applying this broadly" below) turned up a second,
equally mechanical source of missed branches: every `@Serializable data class` used only as a
response DTO (encoded to JSON in every test, never decoded from JSON) leaves its
kotlinx.serialization-generated `deserialize()` dispatch branches unreached. Confirmed by reading
the JaCoCo HTML report directly, e.g. `PromotionDomain.kt`: every missed-branch line is the
`@Serializable` annotation line itself ("1 of 18 branches missed", "1 of 138 branches missed", ...),
never a line inside the class body — that is JaCoCo attributing the compiler-generated companion
serializer's branches back to the annotation. Same shape confirmed in `OrderDomain.kt`,
`shared/common`'s `Money.kt`/`Pagination.kt`, `shared/error-handling`'s `ApiError.kt`, and
`shared/kafka`'s `EventEnvelope.kt`. This is not fixable by writing more tests unless the DTO is
also round-tripped through decode, which is not meaningful for a response-only type.

A smaller residual, seen in repository classes with `when (payload) { is A -> ...; is B -> ...; else
-> "unknown" }`-style dispatch over a closed, caller-controlled set of types (e.g.
`OrderRepository.outbox()`, `PromotionRepository.outbox()`): the `else` branch is unreachable in
practice since only the enumerated types are ever passed, and JaCoCo still counts it. These show up
as "1 of N branches missed" on otherwise thoroughly-tested lines (most already exercise every real
conditional) rather than as large round numbers, which is how they were told apart from genuine gaps
during the module-by-module review below.

## Applying this broadly: how each module's tolerance was set

Per module: `git grep`/JaCoCo XML diffing separated line gaps into "inside `Application.kt`" (the
`module()` wiring exception above) vs. "everywhere else" (real code, checked line-by-line — every
module reached 0 real missed lines outside `Application.kt` except identity-service, search-service,
seller-service, `shared/security`, and `shared/service-support`, each fixed with real tests before
any tolerance was recorded for them). Branch gaps were separated the same way: `Application.kt`
branch misses were confirmed as the suspend-lambda artifact above (spot-checked directly in the
JaCoCo HTML for gateway, payment, identity, inventory, admin, and promotion — consistently "N of M
branches missed" on bare route-handler lines); the smaller remainder in domain/repository/DTO files
was spot-checked per the two patterns immediately above. Only after that check did a module's total
missed-branch count go into `toleratedMissedBranches` — never a rounded or estimated figure.

## Worked example: api-gateway

`api-gateway` was brought from "serves only /health and /metrics, no downstream routing" to a
working reverse proxy for all 24 backend services ([ServiceRoutes.kt](../../backend/api-gateway/src/main/kotlin/com/ecommerce/gateway/ServiceRoutes.kt),
[ProxyHandler.kt](../../backend/api-gateway/src/main/kotlin/com/ecommerce/gateway/ProxyHandler.kt)),
with 19 tests covering: routing to the correct service (including the admin sub-namespace, where
`/api/v1/admin/orders` must reach order-service and not admin-service), header/body/method
forwarding, the 404 path for internal-only and unmatched routes, request-id generation/acceptance,
and the generic error-mapping path.

After that, line coverage reached 100%. Branch coverage plateaued at 35/54 (64.8%) — every one of
the 19 remaining missed branches sits on a line that is nothing but a `suspend` lambda call site
(`get("/health/live") { ... }`, `exception<ApiException> { ... }`, `route("/{...}") { handle { ... } }`,
etc.), each reporting "2 of 3 branches missed" regardless of how many ways the route is exercised.
No amount of additional test cases changes this, because the missing branch is the coroutine
resume path, not an untested conditional. `api-gateway` therefore carries a tolerance of `19` in
`toleratedMissedBranches`.

## Adding a new branch-tolerance entry

1. Write real tests until `./gradlew :backend:<module>:test :backend:<module>:jacocoTestReport`
   shows every genuine conditional exercised.
2. Open `build/reports/jacoco/test/html/<package>/<File>.kt.html` for the module and confirm each
   remaining "N of M branches missed" line is a bare `suspend` lambda call site, not application logic.
3. Record the exact missed-branch count from the module's own coverage report (not a rounded
   percentage) in `toleratedMissedBranches`, with the module's Gradle project name as the key.
4. Re-run `./gradlew :backend:<module>:jacocoTestCoverageVerification` to confirm it now passes,
   and `./gradlew jacocoTestCoverageVerification` from the root to confirm the aggregate gate does too.

## Policy: `Application.module()` line coverage requires an integration test, not an exception

Every backend service's `fun Application.module()` — the function that reads `ApplicationConfig`
and constructs the real `ServiceDatabase`, `KafkaOutboxPublisher`, and `HmacJwtAccessVerifier` — is
unreachable from a plain unit test, because `ServiceDatabase` opens a live JDBC connection and runs
Flyway migrations in its constructor (see [ServiceDatabase.kt](../../backend/shared/service-support/src/main/kotlin/com/ecommerce/platform/service/ServiceDatabase.kt)).
This is not a JaCoCo artifact like the branch-coverage issue above — these lines are genuinely
untested application wiring, and it is the same gap the go-live report flagged directly ("Identity
registration/login/refresh full flow: NOT VERIFIED end to end").

Rather than exempt these lines, every service gets a `@Tag("integration")` boot test —
`<Service>ModuleBootIntegrationTest.kt` — that starts real Testcontainers Postgres (and Kafka,
for services with a `KafkaOutboxPublisher`), builds a `MapApplicationConfig` with every key that
service's `module()` requires, calls `application { module() }` inside Ktor's `testApplication`,
and asserts `/health/ready` returns 200. That proves the full wiring — config parsing, DB
connection, Flyway migration, Kafka producer construction, JWT verifier construction, and route
installation — actually succeeds, which no unit test can.

**Scope is deliberately health-check depth, not full business-flow depth**: these tests were
written and validated for compile-correctness and correct Testcontainers configuration in an
environment with no Docker daemon available (confirmed by running them and observing the failure
stop exactly at "Could not find a valid Docker environment," after fixing two real issues that
surfaced before that point — see `payment-service`'s version for the reference implementation).
Exercising a full authenticated business endpoint per service, matching every request/response DTO
exactly, was deliberately deferred: it would be materially higher-risk to ship unverified. Whoever
runs these with real Docker (`./gradlew integrationTest -PrunIntegration=true`) is the first to
learn whether each one passes — treat a first real run as required validation, not a formality.

Because `jacocoTestReport`/`jacocoTestCoverageVerification` (both per-module and the root
aggregate) now fold in `jacoco/integrationTest.exec` whenever it exists, `module()` line coverage
only shows as closed after the integration lane has actually been run at least once with Docker
available. Without that, `module()` correctly still shows as uncovered — that is accurate, not a
regression.
