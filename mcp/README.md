# DeepSQL CLI + MCP server

The `@deepsql/mcp` package ships three things in one binary:

- **`deepsql`** — a CLI for talking to a self-hosted DeepSQL backend from a
  terminal (auth, connections, SQL execution, plan analysis, index
  suggestions, slow-query analyses, admin ops).
- **`deepsql agent`** (or just **`deepsql`** in a terminal) — the **DeepSQL
  Agent**, an interactive DBA/BI chat TUI. It uses your saved login and the
  backend proxies the model, so no LLM key is needed; the first run installs
  the agent runtime. See [DBA Agent TUI](#dba-agent-tui-deepsql-agent).
- **`deepsql mcp`** — a stdio MCP server that exposes the same backend to
  editor agents (Claude Code, Cursor, Codex, Claude Desktop) so they can
  query and reason about the user's databases.

Both share one auth file (`~/.config/deepsql/auth.json`, mode 0600). Log
in once with `deepsql login`; the MCP server uses the same token
automatically — no token needs to be embedded in your editor's config.

One profile is saved per DeepSQL URL, and logging in to a second host does
**not** make it active. Check which host bare `deepsql` will use:

```bash
deepsql config show                    # list profiles, * marks the default
deepsql config set-default <url>       # choose which host bare `deepsql` uses
deepsql config remove <url>            # forget a host you no longer run
```

## Install

```bash
npm install -g @deepsql/mcp@latest
deepsql --version
```

Requires Node ≥ 20.

## Quick start

```bash
deepsql login --url https://your-deepsql-host.example.com
deepsql connections list
deepsql connections use <connection-name>
deepsql query "SELECT 1 AS ok"
```

## DBA Agent TUI (`deepsql agent`)

```bash
deepsql login --url https://your-deepsql-host.example.com
deepsql            # bare command in a terminal launches the Agent TUI
# or, explicitly:
deepsql agent
```

The **DeepSQL Agent** is an interactive terminal chat for DBA, BI, and
database-guard work, backed by the same DeepSQL brain the MCP server
exposes. It includes a DBA persona, read-only-by-default tool scoping, and
DeepSQL skins.

- **No LLM key needed** — the DeepSQL backend proxies the model
  (`/api/llm/v1`), authenticated by your `deepsql login` token. The agent
  never sees a provider key.
- **Runtime install** — by default `npm install -g @deepsql/mcp` pre-installs
  the agent runtime (pinned to a known-good commit) so the first `deepsql` is
  instant. It's best-effort and never fails the npm install, and skips
  automatically in CI and non-global installs. Opt out with
  `DEEPSQL_SKIP_AGENT_SETUP=1` — pure-MCP users (Claude Code / Cursor) then
  don't pull the runtime, and it installs lazily on first `deepsql agent`
  instead. The per-user `deepsql` profile is provisioned on first launch after
  login (it needs your token).
- **Read-only by default** — only the DeepSQL tools, memory, and skills are
  enabled; host-affecting toolsets (terminal/file/code-exec/browser) are
  disabled. `apply_index_recommendation` stays server-side confirm-gated.
- Bare `deepsql` only launches the TUI when stdout is a TTY; piped or
  scripted `deepsql` still prints help, so automation is unaffected.

## Wire into your editor (one command per editor)

The installer writes the MCP server entry into the editor's config AND
installs a "DBA consult" skill that auto-triggers when the user asks
the agent to do database work:

```bash
deepsql mcp config --install --for claude-code      # via `claude mcp add --scope user`
deepsql mcp config --install --for claude-desktop   # macOS ~/Library/.../Claude/...
deepsql mcp config --install --for cursor           # ~/.cursor/mcp.json + ~/.cursor/rules/deepsql.mdc
deepsql mcp config --install --for codex            # ~/.codex/config.toml + ~/.codex/AGENTS.md
```

Pass `--print` to see what would be written without touching disk.
Pass `--no-skill` to install only the MCP entry. Pass `--force` to
overwrite a stale entry. See `deepsql mcp --help` for details.

Restart the editor for the entry to load.

## What the MCP server exposes

**44 tools** as of 0.26.0. Read-only at the schema/retrieval/diagnostics
layer; policy-gated at the SQL layer (only `execute_sql`,
`analyze_query_plan` with `useAnalyze=true`, and
`apply_index_recommendation` can write — the rest are reads or low-stakes
triggers like `reinit_connection_brain` and `acknowledge_growth_anomaly`).

The surface mirrors the `deepsql` CLI for every read/diagnostic
operation. **What's intentionally NOT in MCP**: connection write ops
(`add`/`update`/`remove` — they'd require plaintext DB credentials in
agent conversation history; manage at a TTY via `deepsql connections
add` instead), interactive auth flows (`login`/`logout`/`setup`/`mcp
config --install`), the SSE streaming `slow-queries optimize` flow, and
per-user admin ops (`users`/`access`/`permissions`).

### Connections + identity (5)

| Tool | Purpose |
|---|---|
| `list_connections` | Connections this token has access to |
| `get_current_user` | Authenticated user + role + bound DeepSQL host |
| `show_connection` | One connection's saved config, secrets masked |
| `test_connection` | Run the privilege report (+ SSH check); reuses saved creds — no plaintext crosses the wire |
| `reinit_connection_brain` | Trigger a fresh schema scan + brain re-embed |

### Schema + retrieval brain (8)

| Tool | Purpose |
|---|---|
| `get_schema` | Cached schema metadata (tables, columns, FKs, types) |
| `get_database_objects` | Tables, views, functions, procedures |
| `get_brain_context` | Retrieval brain plus `callerCapabilities` (what this user can enforce) |
| `list_business_rules` | Active business rules and SQL guardrails for a connection |
| `get_relationships` | Inferred + validated foreign keys with confidence scores |
| `list_brain_recommendations` | The brain's AI-proposed notes to review (do not offer accept unless caller can write) |
| `save_brain_note` | Save a shared brain fact only when the user asked and `canManageContent` is true |
| `list_brain_notes` | Knowledge already saved to the brain (filterable) |

### Anti-patterns + daily digest (4)

| Tool | Purpose |
|---|---|
| `get_anti_patterns` | Schema-level or query-level anti-patterns |
| `get_latest_digest` | Most recent DeepSQL daily digest (slow queries + AI commentary) |
| `list_digests` | Recent digest metadata (find one by date) |
| `get_digest_by_id` | Full digest body |

### Index advisor — workload-weighted + apply tool (5)

| Tool | Purpose |
|---|---|
| `get_index_recommendations` | Pre-computed top-N workload-weighted index recommendations |
| `apply_index_recommendation` | Apply (or dry-run with HypoPG) an index recommendation and measure benefit |
| `list_index_recommendations` | Browse the full recommendation history by status |
| `refresh_index_recommendations` | Force a fresh accumulation cycle (skip 6-hour scheduler wait) |
| `dismiss_index_recommendation` | Reject a recommendation explicitly |

### Index catalog diagnostics — live pg_stat_* / sys.* probes (5)

| Tool | Purpose |
|---|---|
| `get_missing_indexes` | Schema-walk view of suspected missing indexes |
| `get_index_health` | Total/bloated/unused/duplicate/biggest indexes summary |
| `get_unused_indexes` | Indexes with zero/near-zero scans (drop candidates) |
| `get_duplicate_indexes` | Redundant prefix-duplicate indexes |
| `get_table_index_usage` | Per-table scan/read/fetch counts on every index |

### Slow queries (9)

| Tool | Purpose |
|---|---|
| `analyze_slow_queries` | Recent slow queries with fingerprints, durations, examples (triggers fresh collection) |
| `get_latest_slow_query_analysis` | Read the most recent persisted analysis (no new work) |
| `list_slow_query_history` | List past analysis runs (compact metadata) |
| `get_slow_query_timeline` | Day-by-day timeline for one fingerprint from the 30-day store |
| `get_query_regressions` | Slow queries that regressed on the latest daily run |
| `list_tracked_queries` | All fingerprints tracked in the 30-day store |
| `get_slow_query_customers` | Tenants ranked by total slow-query load |
| `get_query_samples` | Literal SQL samples (with bind values) for one fingerprint |
| `get_slow_query_insights` | Pre-computed AI insights — hotspots / remediation / tail-risk / plan-drift / skew |

(`optimize_slow_query` is also in MCP — see the slow-query family.)

### Growth analytics (5)

| Tool | Purpose |
|---|---|
| `get_table_growth` | Per-table size/row growth from persistent stats history |
| `get_growth_anomalies` | DeepSQL-flagged sudden growth spikes with severity and root-cause hints |
| `acknowledge_growth_anomaly` | Mark an anomaly as expected (silences `unacknowledgedOnly` queries) |
| `get_growth_config` | Read current alert thresholds and sensitivity |
| `set_growth_config` | Update alert thresholds (admin-gated server-side) |

### Plan + execute (2 — the write-capable pair)

| Tool | Purpose |
|---|---|
| `execute_sql` | Run any SQL — backend enforces role-based policy (developers read-only; admins can run DML and CREATE/ALTER with two-step confirm; DROP/TRUNCATE blocked) |
| `analyze_query_plan` | AI-enriched plan analysis (parsed plan tree, performance issues, index recommendations, written summary that uses the connection's schema + business rules) |

EXPLAIN and EXPLAIN ANALYZE are just SQL — pass them as the query to
`execute_sql`. For the AI-enriched plan analysis with the LLM-written
summary, use `analyze_query_plan`.

## Runtime guidance for agents

`CLAUDE.md` (bundled in this package, at
`node_modules/@deepsql/mcp/CLAUDE.md` after install) is the runtime
guide for editor agents that have these tools loaded. It covers the
"DBA consult" pattern, decision tree, hard rules around the role-gated
mutation flow, and common foot-guns. The installer also drops a
shortened, trigger-focused version of that guide as a native skill so
the pattern fires automatically on phrases like "add a table", "write
a migration", "design a schema", "query the database".

## Agent-driven setup (one paste)

For a fresh customer install, paste `AGENT-SETUP.md` (bundled at
`node_modules/@deepsql/mcp/AGENT-SETUP.md`) into Claude Code / Cursor /
Codex. The agent walks the user through install, login, connection
registration, editor integration, and end-to-end validation in ~5
minutes.

## Manual install (only if you don't want the CLI shim)

If you'd rather skip the `deepsql mcp config --install` flow and wire
the editor config by hand, see the example files bundled with this
package: `claude_desktop_config.customer.example.json` and
`codex_config.customer.example.toml`. The token-embedded shape in
those examples still works, but `deepsql mcp config --install` is the
recommended path now.

## Run the server directly (advanced)

```bash
deepsql mcp                # uses the saved profile + auth token
npx -y @deepsql/mcp        # one-off invocation without install
```

Both work; the CLI shim is preferred because it shares the saved
profile and never asks you to paste a token into editor config.

## Environment variables

| Variable | Required | Used by | Example |
|----------|----------|---------|---------|
| `DEEPSQL_API_BASE_URL` | for npx-style invocation | `deepsql mcp` if no saved profile | `https://customer-deepsql.example.com/api/` |
| `DEEPSQL_AUTH_TOKEN` | for npx-style invocation | bearer for backend; `deepsql login` writes one to the profile file | `dsql_mcp_…` |
| `DEEPSQL_MCP_USER_ID` | no | identifies the editor that invoked the MCP server in audit logs | `claude-desktop` / `cursor-mcp` / `codex-mcp` |
| `DEEPSQL_CALLER_AGENT` | no | overrides the CLI's audit identity when an agent shells out to `deepsql` | `claude-code` |
| `DEEPSQL_MCP_TIMEOUT_MS` | no | per-request timeout for MCP server calls | `120000` |

If `deepsql login` has been run, both the CLI and the spawned MCP
server pick up `DEEPSQL_API_BASE_URL` + `DEEPSQL_AUTH_TOKEN` from
`~/.config/deepsql/auth.json` automatically — you don't need to set
the env vars for either.
