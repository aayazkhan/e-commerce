# Deployment smoke tests

`ops/release/smoke.sh` is the safe post-deployment smoke entry point. It always checks gateway liveness, readiness, and metrics. Public catalog/search checks are opt-in with `RUN_PUBLIC_SMOKE=true` and must point at a staging or provider-sandbox environment with no real order/payment side effects.

```sh
BASE_URL=https://staging-api.example.invalid \
RUN_PUBLIC_SMOKE=false \
ops/release/smoke.sh
```

Do not run checkout/payment writes as a production smoke test. Use provider sandbox credentials and synthetic identities in staging.
