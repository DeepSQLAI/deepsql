# DeepSQL

![DeepSQL CLI showing suggested DBA, BI and Guardian prompts, connected databases, and brain initialization progress](docs/assets/deepsql-cli.png)

**The database agent for Postgres and MySQL** Point it at PostgreSQL or
MySQL and ask questions in plain English — schema exploration, query generation, slow
query analysis, index recommendations, generated dashboards. Bring your own LLM: OpenAI,
Azure OpenAI, or any OpenAI-compatible endpoint, including one running on your own
hardware. No vendor account is required and no model provider is hardcoded.

Everything runs in your environment. Database credentials are encrypted in a local vault,
and nothing leaves the machines you control except the prompts you send to the LLM
endpoint you configured.

📄 **[Read the whitepaper](https://deepsql.ai/whitepaper)** — the architecture and the
reasoning behind it.

---

## What it does

Your 24/7 DBA and data engineer. It answers BI questions, fixes slow queries, and watches
your schema — all from one shared brain.

- **Answers BI questions.** Ask in plain English. The agent loads your business context,
  resolves the schema, drafts and validates SQL, then executes it read-only — with every
  step you can inspect. The hand-written SQL editor is the only path that can mutate, and
  only for a confirming admin.
- **Fixes slow queries.** Reads `pg_stat_statements` or the MySQL slow log, groups queries
  by fingerprint, ranks them by cost, and flags regressions against their baseline.
- **Index recommendations.** Advises, and can apply them for you — `CREATE INDEX
  CONCURRENTLY` on PostgreSQL, so no table lock.
- **Watches your schema.** Tracks what changed and what needs attention, so drift surfaces
  before it breaks a query or a dashboard.
- **A brain that knows your business.** Teach it your metrics, rules and conventions once —
  MRR, active accounts, currency handling — and every query, dashboard and recommendation
  uses the same governed definitions.
- **Dashboards without the analyst backlog.** An agent writes a single self-contained HTML
  document, rendered in a sandboxed iframe with no network access. It reads data only
  through a read-only query bridge back to the backend — the agent gets creative freedom,
  the database keeps its guard rail.
- **Ask it from anywhere.** The web UI, your terminal, or a Slack channel. The MCP server
  gives coding agents (Claude Code, Cursor, Codex, Claude Desktop) the same capabilities
  over stdio.
- **Postgres and MySQL, in your infra.** One dialect registry, read-only execution, and SSH
  tunnelling to reach databases behind a bastion.

---

## Quick start

There are no prebuilt images and no container registry. Docker Compose builds the backend
and frontend from this checkout.

```bash
git clone <this-repo> deepsql
cd deepsql
cp .env.example .env      # required — compose reads .env and will not start without it
$EDITOR .env              # set DEEPSQL_CHAT_* — see "Bring your own LLM" below

./scripts/self-host/install.sh
```

`install.sh` generates the JWT secret, the credential-vault encryption key and the vault DB
password, prompts for your LLM key and the first admin account, builds both images, starts
the stack, and verifies pgvector is live. Then open http://localhost:3000 and log in with
the admin email and password you entered.

**The first build takes several minutes.** It compiles the Spring Boot backend with Maven
inside the container and bundles the frontend with Vite. It has not hung — later builds
reuse the Docker layer cache and are fast.

Prefer to drive Compose yourself?

```bash
# .env.example ships these two empty on purpose — they are validated secrets,
# not free text, and the backend refuses to start without them.
printf 'SECURITY_JWT_SECRET=%s\n' "$(openssl rand -base64 64 | tr -d '\n')" >> .env
printf 'ENCRYPTION_KEY=%s\n'      "$(openssl rand -base64 32)"             >> .env

docker compose up -d --build
```

Back up `ENCRYPTION_KEY`. It encrypts every database credential you store, and losing it
means re-entering all of them.

That builds and starts everything, but leaves you without a way to log in: there is no
seeded account, self-service signup is disabled, and the setup wizard's
`POST /setup/initialize` is disabled. The first user is created through a localhost-only
bootstrap endpoint — set `SECURITY_ADMIN_BOOTSTRAP_ENABLED=true` and `ADMIN_BOOTSTRAP_SECRET`
in `.env` and call it yourself, or run `install.sh`, which does exactly that and then turns
the flag back off.

### Requirements

- Docker Engine with Docker Compose v2 (`docker compose version`)
- `curl` and `openssl` (used by `install.sh`)
- About 4 GB of memory available to Docker — the backend JVM is configured with a 3 GB max heap

---

## Bring your own LLM

Chat and embeddings are configured independently, so they can point at different providers,
keys, or endpoints. One provider id ships today — `openai` — and it is the only one you
need: it speaks OpenAI, Azure OpenAI, and any OpenAI-compatible server.

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=sk-your-key
DEEPSQL_CHAT_ENDPOINT=https://api.openai.com/v1
DEEPSQL_CHAT_MODEL=gpt-4o

DEEPSQL_EMBEDDING_PROVIDER=openai
DEEPSQL_EMBEDDING_API_KEY=sk-your-key
DEEPSQL_EMBEDDING_ENDPOINT=https://api.openai.com/v1
DEEPSQL_EMBEDDING_MODEL=text-embedding-3-large
```

`_PROVIDER` gates the rest: with it unset, no other variable in that group is read.
`_ENDPOINT` has no working fallback for chat, so set it explicitly even for OpenAI.
Embeddings are optional in the sense that the app still starts without them — retrieval
just stays keyword-only until they are configured.

**Azure OpenAI** — an `.azure.com` or `.azure-api.net` endpoint switches authentication to
the `api-key` header automatically, and `_MODEL` is your *deployment* name:

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=your-azure-openai-key
DEEPSQL_CHAT_ENDPOINT=https://your-resource.cognitiveservices.azure.com/
DEEPSQL_CHAT_MODEL=your-deployment-name
```

**Ollama, vLLM, LM Studio, TGI** — anything that speaks the OpenAI wire format:

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=ollama
DEEPSQL_CHAT_ENDPOINT=http://host.docker.internal:11434/v1
DEEPSQL_CHAT_MODEL=llama3.1
```

**LiteLLM proxy** — point at the proxy, authenticate with a virtual key, and use your own
model alias. Chat, embeddings and brain initialisation have been verified end to end
against a self-hosted LiteLLM:

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=sk-your-litellm-virtual-key
DEEPSQL_CHAT_ENDPOINT=http://litellm:4000/v1
DEEPSQL_CHAT_MODEL=your-alias

DEEPSQL_EMBEDDING_PROVIDER=openai
DEEPSQL_EMBEDDING_API_KEY=sk-your-litellm-virtual-key
DEEPSQL_EMBEDDING_ENDPOINT=http://litellm:4000/v1
DEEPSQL_EMBEDDING_MODEL=your-embedding-alias
```

One thing to know about alias naming: `_USE_RESPONSES_API` defaults to `auto`, which
decides from the **model name**, not from what your endpoint actually implements. An alias
beginning `gpt-5`, `o1`, `o3`, `o4` or `codex` selects the Responses API. If your gateway
does not serve `/v1/responses`, either avoid those prefixes or set
`DEEPSQL_CHAT_USE_RESPONSES_API=false`. Aliases that look nothing like an OpenAI model —
the usual case — resolve to chat completions on their own.

Optional tuning, same prefix, all defaulted: `_TEMPERATURE`, `_API_VERSION` (the Azure REST
version), `_USE_RESPONSES_API` (`true` / `false` / `auto`).

> Environment variables are the way to configure the LLM. The onboarding wizard in the UI
> writes a different, older set of config keys that the resolver does not read — it will not
> configure a provider for you.

---

## MCP server

The server lives in `mcp/` and exposes 44 tools that wrap the backend API, so agents reuse
the same orchestration, retrieval and guardrails instead of getting raw database
credentials. SQL execution is read-only-enforced before it reaches the backend.

```bash
DEEPSQL_API_BASE_URL=http://localhost:8080/api/ \
DEEPSQL_AUTH_TOKEN=<your-deepsql-token> \
npm run mcp:phase1
```

It has no npm dependencies of its own — `npm run mcp:phase1` is just
`node mcp/deepsql-phase1-server.js`, so it runs straight from a fresh clone.

See [`mcp/README.md`](mcp/README.md) for the CLI, editor configuration and the full tool
table, and [`docs/root/MCP_PHASE1.md`](docs/root/MCP_PHASE1.md) for the rollout notes.

---

## Development

Run the stateful dependencies in Docker — PostgreSQL needs the **pgvector** extension, which
is tedious to install by hand — and everything else natively for hot reload.

```bash
docker compose up -d postgres valkey    # requires .env to exist

cd backend && mvn spring-boot:run       # http://localhost:8080/api
npm install && npm run dev              # http://localhost:3000
```

You will need **JDK 25** and **Maven 3.9+** (there is no Maven wrapper in the repo), and
**Node 22**, which is what the frontend image builds with.

The backend needs the same environment as the container: at minimum `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `SECURITY_JWT_SECRET`, `ENCRYPTION_KEY`, and the `DEEPSQL_CHAT_*` group.
Authentication is on by default in every profile and there is no `admin`/`admin` shortcut:
either create the first account through the bootstrap flow above, or set
`SECURITY_AUTH_ENABLED=false` to let the backend accept unauthenticated API calls while you
work on it.

Useful commands:

```bash
npm run lint             # eslint
npm run build            # production frontend bundle
cd backend && mvn test   # backend test suite
```

---

## Operating the stack

```bash
./scripts/self-host/status.sh                    # compose ps + health probes
./scripts/self-host/smoke-test.sh                # end-to-end check against the vault DB
./scripts/self-host/uninstall.sh                 # stop and remove containers, keep data
./scripts/self-host/uninstall.sh --purge-data    # also drop the volumes
```

To pick up new code, pull and rebuild:

```bash
git pull && docker compose up -d --build
```

### Ports

| Service  | Port | Override                |
|----------|------|-------------------------|
| Frontend | 3000 | `DEEPSQL_FRONTEND_PORT` |
| Backend  | 8080 | `DEEPSQL_BACKEND_PORT`  |
| Postgres | 5432 | `DEEPSQL_POSTGRES_PORT` |
| Valkey   | 6379 | `DEEPSQL_VALKEY_PORT`   |

### Configuration reference

Everything lives in `.env`, which is documented inline. The variables that matter most:

| Variable | Purpose |
|---|---|
| `DEEPSQL_CHAT_PROVIDER`, `_API_KEY`, `_ENDPOINT`, `_MODEL` | The chat model. Required. |
| `DEEPSQL_EMBEDDING_PROVIDER`, `_API_KEY`, `_ENDPOINT`, `_MODEL` | Embeddings for retrieval. Optional; without them retrieval is keyword-only. |
| `SECURITY_JWT_SECRET` | Signs session tokens. Generate with `openssl rand -base64 64`. |
| `ENCRYPTION_KEY` — or `ENCRYPTION_KEYS` + `ENCRYPTION_KEY_ID` | AES-GCM key(s) for the credential vault. The backend refuses to start without one. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | The vault database. Compose points these at its own `postgres` service. |
| `SPRING_PROFILES_ACTIVE` | `prod` for self-hosting — hardened defaults. |
| `SECURITY_AUTH_ENABLED` | Set `false` only for local development. |
| `SECURITY_ADMIN_BOOTSTRAP_ENABLED`, `ADMIN_BOOTSTRAP_SECRET` | Gate the localhost-only first-admin endpoint. |
| `CORS_ALLOWED_ORIGINS` | Browser origins allowed to call the API. |
| `VECTOR_STORE_TYPE` | `pgvector` (the self-hosting default) or `azure`. |
| `EMBEDDING_FAIL_OPEN` | Whether a failed embedding call degrades silently or raises. |
| `SLACK_*`, `EMAIL_*` | Optional Slack bot and SMTP. |

### Telemetry

The backend can report anonymous install and usage counters to PostHog. **No project key
ships with this repository**, so the sink is a no-op unless you configure
`deepsql.telemetry.posthog-project-key` yourself. `DO_NOT_TRACK=1` or
`DEEPSQL_TELEMETRY_DISABLED=1` disables it outright, as does the admin toggle.

---

## Stack

Spring Boot 4 on Java 25 · React 19 + Vite · PostgreSQL with pgvector · Valkey for
caching · nginx.

## Documentation

- [Whitepaper](https://deepsql.ai/whitepaper) — architecture and design rationale
- [`docs/README.md`](docs/README.md) — documentation index
- [`AGENTS.md`](AGENTS.md) — codebase map
- [`mcp/README.md`](mcp/README.md) — CLI and MCP server

## License

[Apache 2.0](LICENSE).
