# Phase 10A coverage baseline

Measured on 2026-08-20 from `build/reports/jacoco/aggregate/jacoco.xml` after:

```bash
./gradlew clean build jacocoTestReport --no-daemon
```

| Counter | Covered | Total | Coverage |
| --- | ---: | ---: | ---: |
| Line | 421 | 4,346 | 9.69% |
| Branch | 150 | 8,604 | 1.74% |
| Instruction | 4,175 | 145,353 | 2.87% |
| Method | 156 | 4,849 | 3.22% |
| Class | 58 | 1,062 | 5.46% |

No production classes or branches are excluded. Reports are generated in `build/reports/jacoco/aggregate/` as HTML, XML, and CSV.

The baseline is substantially below the Phase 10A target. The existing aggregate gate is functioning: `./gradlew :jacocoTestCoverageVerification --no-daemon` fails with `LINE coverage is 421/4346` and `BRANCH coverage is 150/8604`.

## Test baseline

| Measure | Count |
| --- | ---: |
| Kotlin production source files | 114 |
| Kotlin test source files | 38 |
| Declared JUnit tests | 50 |
| Fast tests executed | 42 |
| Tagged integration tests | 8 (excluded from this unit baseline) |
| OpenAPI contract documents | 24 |

## Interpretation

The low baseline is caused primarily by untested application wiring, repository branches, generated serialization paths, and services with no test source—not by missing integration infrastructure. Phase 10A therefore adds unit tests for pure business/domain paths first, then boundary behavior using in-memory fakes or mocks. It must not use coverage exclusions or tests that only invoke code without asserting behavior.
