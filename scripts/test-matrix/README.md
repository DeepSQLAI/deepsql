# Engine acceptance matrix

Runs the same end-to-end pass against several database engines and prints a
pass/fail matrix: register a connection → brain initialisation → ask questions →
check the answers against values fixed by the seed.

Every target is seeded with an identical synthetic schema, so results compare
directly. If two engines disagree, that is a finding about DeepSQL rather than a
difference in the fixtures.

## Running it

The DeepSQL stack must already be up, and the account must be able to manage
connections. Credentials come from the environment; nothing is hardcoded.

```bash
export DEEPSQL_EMAIL=admin@example.com
export DEEPSQL_PASSWORD=...

./run-matrix.sh                          # PostgreSQL 17, 18 and MySQL 8.0, 8.4
./run-matrix.sh --engines pg18,my84      # a subset
./run-matrix.sh --keep                   # leave containers and connections for inspection
```

Exits non-zero if any target fails, so it can gate a release.

| Variable | Default | |
|---|---|---|
| `DEEPSQL_URL` | `http://localhost:8080/api` | |
| `DEEPSQL_EMAIL`, `DEEPSQL_PASSWORD` | — | required |
| `DEEPSQL_TEST_NETWORK` | `deepsql_default` | Docker network the backend can resolve |
| `DEEPSQL_TEST_DB_PASSWORD` | `matrixpw` | password for the throwaway containers |
| `DEEPSQL_INIT_TIMEOUT` | `1800` | seconds to wait for brain init |
| `DEEPSQL_ANSWER_DIR` | `$TMPDIR/deepsql-matrix-answers` | where full answers are written |

Local containers are created and destroyed by the script, including on failure or
Ctrl-C. Connections it registers are deleted the same way.

Every answer is written to `DEEPSQL_ANSWER_DIR` in full, and a failing target
prints the path. The matrix line truncates to 60 characters, which is not enough
to tell a wrong answer from a differently formatted one — read the file before
concluding anything from a red row.

Numbers are compared with digit grouping removed, so `$8,092.00` and `8092.00`
both satisfy an expected `8092`. Models differ on this and both spellings are
correct; a matrix that failed one of them would be testing prose style. The
normalisation is narrow — only a comma between a digit and exactly three digits
is dropped — so a genuinely wrong number still fails.

## Testing a managed cloud database

`--external` points the same acceptance pass at a database you already have. The
script never creates, seeds or deletes external targets — seed it yourself with the
matching `seed-*.sql`, then:

```bash
./run-matrix.sh --external 'rds-pg:postgres:host.rds.amazonaws.com:5432:shopdb:app:secret'
```

Spec format is `name:type:host:port:database:user:password`.

The reason to run cloud targets through the same script is cost: a managed instance
bills for as long as it exists, and this reduces the paid window to "seed, run, read
the matrix". Create the instance, run this, destroy it.

## What the matrix does and does not cover

Covered: engine and version differences, the privilege probes DeepSQL requires
(`pg_stat_statements` on PostgreSQL, `PROCESS` / `SHOW PROCESSLIST` on MySQL),
schema introspection, brain initialisation, and both the BI and metadata answer
paths.

Not covered, and only reachable against real managed instances:

- restricted managed-service roles (no superuser; `pg_stat_statements` gated behind
  a parameter group or database flag)
- real CA chains and `verify-full` TLS
- Aurora's divergence from vanilla PostgreSQL/MySQL
- IAM authentication, Cloud SQL Auth Proxy, Microsoft Entra
- private networking (VPC peering, Private Link, bastion hops)

Worth knowing before reading too much into a green matrix: `cloudProvider` and
`managedService` on a connection are metadata. They are interpolated into the
config-tuning prompt and never branch code, so "the same engine on another cloud"
exercises the same paths. What actually differs between clouds is the list above —
chiefly the privileges the account is granted.

## The LLM axis

`run-llm-matrix.sh` runs the whole engine matrix once per LLM provider, making
provider a second axis. It rewrites the stack's `.env`, restarts the backend, runs
`run-matrix.sh`, and restores the original configuration on exit — including on
failure or Ctrl-C.

```bash
export MATRIX_OPENAI_KEY=...
./run-llm-matrix.sh --llm openai --engines pg18,my84
./run-llm-matrix.sh --llm anthropic,litellm
```

Keys are read from the environment only. They never appear in argv, where `ps`
would show them, and never in a repo file.

Verified end-to-end, every run answering with the seed's ground truth — `Product
23` at `8092.00` ahead of `Product 28` and `Product 22`:

| Provider | Reached via | Chat model | Engines | Result |
|---|---|---|---|---|
| Azure OpenAI | `*.cognitiveservices.azure.com` | gpt-5.4 | pg17, pg18, my80, my84 | pass |
| OpenAI | `api.openai.com/v1` | gpt-5.4-nano | pg18, my84 | pass |
| Anthropic | `api.anthropic.com/v1`, no gateway | claude-haiku-4-5 | pg18 | pass |
| Anthropic | LiteLLM proxy | claude-haiku-4-5 | pg18 | pass |

Engine coverage is not uniform across providers on purpose. The engine axis is the
cheap one and gets the full sweep under the stack's normal provider; the other
providers are checked on a representative target, because what they exercise is
the LLM path, not the engine path.

Two things this establishes. Anthropic needs no gateway and no provider of its
own — it serves an OpenAI-compatible `/v1/chat/completions` with Bearer auth, so
the shipped `openai` provider drives it on configuration alone. And a gateway
changes nothing: Claude direct and Claude through LiteLLM produced the same
answers, which is what makes the pair worth running together — a disagreement
would have isolated the gateway.

Embeddings are the one asymmetry. Anthropic publishes no embeddings API, so the
`anthropic` profile pairs Claude chat with OpenAI embeddings. Chat and embeddings
resolve as independent bundles, so this is a supported configuration rather than a
workaround.

Whatever the provider, embeddings must be 3072-wide. `rag_documents.embedding` is
a single `vector(3072)` column shared by every connection, so a narrower model —
`text-embedding-3-small` at 1536 — is rejected for all connections at once, and
creating a fresh connection does not help. That is the safe failure: the dimension
is enforced rather than silently degrading retrieval.

## The seed

`seed-postgres.sql` and `seed-mysql.sql` build the same five tables with fixed row
counts: 300 customers, 50 products, 500 orders, 800 order items, 250 payments. All
values are generated (`Customer 1`, `user1@example.com`, timestamps offset from
now); there is no real data.

The runner asserts on constants derived from those counts — 300 customers, and
`Product 23` topping revenue at `8092.00`. Changing a seed means updating the
`EXPECT_*` constants at the top of `run-matrix.sh`.
