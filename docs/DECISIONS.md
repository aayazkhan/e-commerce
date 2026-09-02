# Architecture Decision Records

## ADR-001: Service-owned persistence

Decision: each domain service owns its PostgreSQL schema/database boundary and exposes facts through APIs or events.

Trade-off: more operational components and eventual consistency in projections, in exchange for independent scaling, clear ownership, and safer deploys. A shared database would reduce early setup but creates hidden coupling and makes service autonomy illusory.

## ADR-002: KMP for policy, native/platform web for presentation

Decision: KMP owns portable domain/application rules, contracts, sync, and data ports; native mobile and SEO-first web technologies own platform presentation and browser/device integration.

Trade-off: some presentation code is duplicated, but accessibility, SEO, performance, and platform integration improve. Duplication is constrained to UI adapters; business rules remain shared.

## ADR-003: Transactional outbox and idempotent consumers

Decision: state changes and outbox records commit together; relays publish to Kafka; consumers deduplicate with inbox/event IDs and domain uniqueness constraints.

Trade-off: delivery is at-least-once and requires operational replay tooling, but business effects remain safe during retries and process failure.

## ADR-004: Server-authoritative commerce

Decision: the server recalculates price, promotion, tax, shipping, inventory, and payment state for every critical command.

Trade-off: offline checkout cannot be fully authoritative, but client tampering and stale inventory cannot create financial inconsistency.

## ADR-005: Gateway plus private service APIs

Decision: clients use one versioned gateway/BFF; services use private authenticated APIs and events.

Trade-off: the gateway is an operational dependency and must avoid business ownership, but clients gain a stable boundary and fewer round trips.

## ADR-006: Incremental backend implementation

Decision: implement and verify the shared backend foundation and one deployable gateway slice before adding business services. Existing service directories remain independently deployable targets; they are not represented as complete until their domain, persistence, API, events, security, tests, and operations are implemented.

Trade-off: the repository is not immediately feature-complete, but every committed slice is buildable and measurable. This avoids fake repositories, mock commerce behavior, and an untestable all-at-once rewrite.

## ADR-007: Consolidated identity service for Phase 2

Decision: Auth/Identity, User, and Customer Profile are implemented as one `identity-service` with separate domain/application packages and one PostgreSQL ownership boundary for this phase.

Trade-off: a future high-scale deployment may split profile reads from credential/session writes, but splitting them now would create cross-service identity consistency and privacy complexity before there is a measured bottleneck. The service communicates externally through versioned APIs and outbox events, so a later split does not require shared-table access.

## ADR-008: Phase 3 product discovery boundaries

Decision: Category, Catalog, Pricing, Media, and Search are independently deployable services. Category, Catalog, and Pricing own separate PostgreSQL migration spaces; Media owns metadata in PostgreSQL and binary objects in S3-compatible storage; Search owns only a derived OpenSearch alias and consumes versioned Kafka events.

Category uses a materialized path because category-tree reads are much more frequent than hierarchy mutations. Product and price events use the aggregate ID as the Kafka key. Search reindexing writes a new index and swaps the alias atomically, so a failed rebuild does not replace the live projection.

Trade-off: reads across service boundaries are eventually consistent, and full search reindexing uses the catalog read API rather than sharing its database. This preserves source-of-truth ownership and makes OpenSearch or Redis failure non-authoritative.
