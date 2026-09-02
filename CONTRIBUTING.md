# Contributing

## Architectural rules

1. Keep dependencies flowing toward domain policy and stable ports.
2. Do not import platform UI, networking, database, or provider SDKs into domain modules.
3. A service owns its data; use APIs/events rather than another service’s tables.
4. New commands define idempotency, authorization, audit, retries, and failure behavior.
5. New events define schema version, owner, compatibility, retention, and replay behavior.
6. Money uses integer minor units and an explicit currency.
7. User-visible text is localized; configuration is injected, not hard-coded.
8. Add tests, telemetry, and documentation with the feature.

## Change workflow

- create or update an ADR for a boundary or cross-cutting decision;
- update the OpenAPI/event contract before implementation;
- implement the smallest vertical slice across domain, application, data, API, and UI;
- run formatting, static analysis, unit tests, contract tests, and focused integration tests;
- include migration and rollback notes for schema changes;
- include observability and accessibility acceptance criteria in the PR.

## Review checklist

- authorization is checked at the owning service;
- retries are bounded and safe;
- logs do not contain secrets or unnecessary PII;
- offline and stale states are explicit where applicable;
- loading, empty, error, and success states exist;
- API compatibility and event evolution are covered;
- tests cover invariants, not only happy paths;
- performance impact is measured or explained.

