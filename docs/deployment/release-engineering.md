# Release engineering

## Release identity

Every release records commit, semantic release/version, immutable image tag, image digests, build timestamp, migration version, feature-flag snapshot, environment, and deployment timestamp. The release workflow writes `release-metadata.json` and `image-digests.env` as CI artifacts.

Production registry policy must make commit tags immutable. A production manifest must not contain `latest`, `local`, `SNAPSHOT`, or a placeholder tag. The current example overlay contains placeholders intentionally and cannot be promoted until CI renders it with the release tag and records digest evidence.

## Promotion flow

```text
pull request → build/test/contract/manifest/security gates
  → tag/dispatch → build all images + SBOM + provenance + vulnerability scan
  → publish immutable images → staging approval → rollout → smoke/E2E
  → production approval → canary/rolling rollout → observe → expand
```

The repository does not have a live registry or cluster connection, so the workflow is implemented but not executed here.

## Rollout policy

- Use 1% → 5% → 25% → 50% → 100% only when the ingress/controller supports weighted traffic or a canary controller.
- Otherwise use the Kubernetes rolling update with `maxUnavailable: 0`, PDB, readiness, and a documented observation window.
- Abort on baseline-relative 5xx/P99/checkout/payment thresholds; do not automatically reverse durable payment or inventory state.
- Roll back application traffic/code with `ops/release/rollback.sh`; use a forward-compatible migration plan for database changes.
