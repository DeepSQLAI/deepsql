## Test Layout

Framework-native test code stays in the conventional locations:

- `backend/src/test/java` for Java/JUnit tests
- `backend/src/test/resources` for backend test config

Reusable suite manifests and suite runner scripts live under `tests/suites/`:

- `tests/suites/brain-retrieval/`
  - `brain-retrieval-smoke-test-cases.json` — short fixture used by the local
    regression suite
  - `brain-retrieval-test-cases.json` — full fixture exercising BI retrieval,
    schema exploration, business-rule retrieval, and inferred relationships
  - `run-brain-retrieval-tests.sh` — runner that hits the brain endpoints
    directly (no chat agent in the loop)
- `tests/suites/mcp/`
  - `aws_sf_prod-product-suite.json`
- `tests/suites/postgres-sim/`
  - `schema-bi-prompts.json` — reference prompts shared across simulator work
  - `postgres-sim-brain-retrieval-test-cases.json` — full brain-retrieval
    fixture targeting the logistics simulator schema
  - `run-postgres-sim-regression.sh` — runs the brain-retrieval suite against a
    simulator connection

### Removed suites

The chat-agent-based suites (`chat-resilience`, `sql-accuracy`,
`schema-metadata`, `performance-monitoring`) were retired because the project
no longer ships its own chat agent. Their coverage of BI accuracy and schema
exploration is now expressed as direct assertions on the brain's retrieval
APIs — see the `brain-retrieval` suite. Reports/summaries from those legacy
runs may still exist under `tests/suites/brain-only-report.json` /
`tests/suites/v1-focus-decision.md` as historical context.
