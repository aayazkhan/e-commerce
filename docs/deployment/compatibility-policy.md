# API and event compatibility policy

- APIs are versioned under `/api/v1`; additions are backward-compatible during the supported mobile/web client window.
- Required-field additions, type changes, removed fields, changed error semantics, and authorization changes require a versioning/deprecation review.
- Each event keeps its `eventType`, `eventVersion`, required fields, and idempotency identity. Additive evolution must remain consumable by older downstream services.
- Deprecation records introduction date, current version, deprecation date, removal date, replacement, and owning team.
- CI parses every OpenAPI contract and validates the release Dockerfile map. A baseline comparison tool such as `oasdiff` and event fixture compatibility tests must be wired once a versioned baseline directory is selected.

Never silently remove an API or change an event schema during a canary.
