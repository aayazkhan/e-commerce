# k6 load scenarios

This is a test harness, not a capacity result. Run only against a disposable, production-like environment with synthetic data:

```sh
BASE_URL=https://staging.example VUS=10 DURATION=2m k6 run tests/load/k6/ecommerce-critical.js
BASE_URL=https://staging.example VUS=10000 DURATION=30m k6 run tests/load/k6/ecommerce-critical.js
```

Progressively increase VUs only after the previous stage passes. Set `ENABLE_WRITES=true` only with isolated users, valid fixtures, and a reconciliation plan. The gateway in this repository currently exposes health/metrics routes only, so catalog/search results must be treated as an environment integration check until real gateway routing is deployed.
