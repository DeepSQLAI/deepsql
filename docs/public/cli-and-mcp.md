---
title: DeepSQL CLI & MCP — Setup and Reference
slug: cli-and-mcp
description: Install the DeepSQL CLI, log in, and wire your AI editor (Claude Code, Claude Desktop, Cursor, Codex) to the DeepSQL MCP server. Complete command reference and MCP tool reference with examples.
keywords: [DeepSQL, CLI, MCP, Claude Code, Cursor, Codex, AI DBA, database agent, model context protocol]
audience: developers
status: stable
---

# DeepSQL CLI & MCP — Setup and Reference

The same DeepSQL backend that powers the web UI is also reachable from your terminal and your AI editor. One npm package ships two things:

- **`deepsql`** — a CLI for talking to a DeepSQL instance (auth, connections, SQL execution, plan analysis, index suggestions, slow-query analyses, admin operations).
- **`deepsql mcp`** — a stdio [Model Context Protocol](https://modelcontextprotocol.io) server that exposes the same backend to editor agents (Claude Code, Cursor, Codex, Claude Desktop) so they can query and reason about your databases.

Both share one auth file at `~/.config/deepsql/auth.json` (mode `0600`). You log in once with `deepsql login`; the MCP server reuses the same token automatically — no token ever needs to be embedded in your editor's config.

> **What you get out of the box:** the MCP install command also installs a small "DBA consult" skill into your editor. The skill teaches the agent to retrieve schema context, business rules, and anti-patterns from DeepSQL *before* it generates DDL or non-trivial SQL — the difference between an agent that ships features and one that quietly duplicates tables.

---

## Install

```bash
npm install -g @deepsql/mcp@latest
deepsql --version
```

Requires **Node 20 or later**. The package installs two binaries on your `$PATH`:

- `deepsql` — the CLI
- `deepsql-mcp` — a thin wrapper around `deepsql mcp` (kept as a separate binary so editor configs that pre-date the unified package still work)

---

## Authenticate

```bash
deepsql login --url https://your-deepsql-host.example.com
```

By default this opens a browser for [PKCE](https://oauth.net/2/pkce/). The flow auto-detects what your machine supports:

| Flag | When to use it |
|---|---|
| `--browser` | Force the browser-callback flow (default on desktops) |
| `--device` | Force the device-code flow (default on headless boxes — SSH, CI, EC2) |
| `--no-browser` | Same as `--device` |
| `--password` | Direct email + password login. Useful on a freshly-installed self-hosted VM before SSO is configured. |
| `--email <addr>` / `--password-stdin` | Non-interactive credentials for `--password` (CI/scripts) |
| `--label <name>` | Label the saved token (shown by `deepsql whoami`) |

On success, the token lands in `~/.config/deepsql/auth.json` with file mode `0600`. To check who you're logged in as:

```bash
deepsql whoami
```

You can save profiles for multiple DeepSQL instances side-by-side (one URL per profile). Switch defaults with:

```bash
deepsql config show
deepsql config set-default https://deepsql.acme.com
```

To revoke a saved token, run `deepsql logout`.

---

## Verify and pin a connection

```bash
deepsql connections list                    # active default marked with *
deepsql connections use prod-postgres       # pin a default for this profile
deepsql query "SELECT 1 AS ok"
```

Most CLI commands default to the active connection. Use `--connection <name>` (or set `DEEPSQL_CONNECTION` in your shell) to override per command.

---

## Wire your AI editor (one command per editor)

The MCP installer writes the DeepSQL server entry into your editor's MCP config **and** installs the DBA-consult skill into the editor's skills directory. Each command below is idempotent — re-run after a release to update both pieces.

| Editor | Install command | What it touches |
|---|---|---|
| **Claude Code** | `deepsql mcp config --install --for claude-code` | Writes `~/.claude.json`; installs the skill into `~/.claude/skills/dba-consult/` |
| **Claude Desktop** | `deepsql mcp config --install --for claude-desktop` | Writes Claude Desktop's `mcp_settings.json` |
| **Cursor** | `deepsql mcp config --install --for cursor` | Writes `~/.cursor/mcp.json` |
| **Codex** | `deepsql mcp config --install --for codex` | Writes `~/.codex/config.toml` |

After install, **restart the editor.** The agent now has DeepSQL tools loaded plus the DBA-consult skill. Confirm with a prompt like *"list my DeepSQL connections."*

### Preview before writing

If you'd rather see the snippets the installer would write without touching disk:

```bash
deepsql mcp config --print --for cursor
```

Useful for code review, CI, or for environments where you want to commit the editor config to a dotfiles repo.

### Useful flags

| Flag | Purpose |
|---|---|
| `--force` | Overwrite an existing DeepSQL entry/skill |
| `--no-skill` | Skip the DBA-consult skill install (server config only) |
| `--path <p>` | Override the default MCP config path (advanced) |
| `--url <url>` | Bind the spawned MCP server to a specific saved profile (when you have more than one) |

---

## CLI reference

Every CLI command is a thin shell over the same DeepSQL backend the web UI uses, with the same RBAC and policy gates. Run `deepsql <command> --help` for full per-command usage.

### Commands

| Command | What it does | Example |
|---|---|---|
| `deepsql login` | Authorize this machine. Saves a token used by every later command and the MCP server. | `deepsql login --url https://deepsql.acme.com` |
| `deepsql logout` | Revoke and forget the saved token. | `deepsql logout` |
| `deepsql whoami` | Show the user behind the saved token. | `deepsql whoami` |
| `deepsql config show` | List saved profiles (one per DeepSQL URL). | `deepsql config show` |
| `deepsql config set-default <url>` | Switch the default profile. | `deepsql config set-default https://prod.deepsql.acme.com` |
| `deepsql connections list` | List databases you can see. The active default is marked with `*`. | `deepsql connections list` |
| `deepsql connections use <name>` | Pin a connection as the default so later commands don't need `--connection`. | `deepsql connections use prod-postgres` |
| `deepsql connections show <name>` | Inspect a single connection (secrets masked). | `deepsql connections show prod-postgres --json` |
| `deepsql connections add` | Create a connection interactively, or `--from-file <p>` for JSON input. Use `deepsql connections schema` to see the expected shape. | `deepsql connections add --from-file ./conn.json` |
| `deepsql connections update <name>` | PATCH-style update — omitted secrets are preserved. | `deepsql connections update prod-postgres --from-file ./patch.json` |
| `deepsql connections remove <name>` | Delete a connection. Pass `--yes` to skip the prompt. | `deepsql connections remove sandbox --yes` |
| `deepsql connections test` | Validate a connection without saving it. | `deepsql connections test --from-file ./conn.json` |
| `deepsql connections init <name>` | Re-run the brain init (schema scan, embeddings, business-rule indexing). `--wait` blocks until COMPLETED/FAILED. | `deepsql connections init prod-postgres --wait` |
| `deepsql schema [tables\|objects]` | Dump the connection's cached schema as JSON. `tables` is the default (columns + FKs); `objects` covers views, procedures, etc. | `deepsql schema tables --connection prod-postgres` |
| `deepsql query "<sql>"` | Run SQL through the same policy gate as the web SQL Editor. Developers can run SELECT/WITH/SHOW/EXPLAIN; admins can also run DML/DDL with confirmation. `DROP` is blocked from this surface. | `deepsql query "SELECT count(*) FROM orders" --limit 1` |
| `deepsql analyze "<sql>"` | AI-enriched plan analysis: parsed tree, performance issues, index recommendations, written summary that uses your schema + business rules. Pass `--analyze` to use EXPLAIN ANALYZE (executes the query). | `deepsql analyze "SELECT * FROM orders WHERE status='OPEN'" --analyze` |
| `deepsql brain-context "<question>"` | Pull the same retrieval context the chat pipeline uses (tables, columns, FKs, business rules, anti-patterns) for a natural-language question. Use in scripts to ground your own agent. | `deepsql brain-context "active subscriptions per plan"` |
| `deepsql business-rules` | List active business rules and SQL guardrails. Pass `--question "..."` to scope to one ask. | `deepsql business-rules --connection prod-postgres` |
| `deepsql relationships` | List inferred + validated foreign-key relationships, with confidence scores for inferred ones. | `deepsql relationships --json` |
| `deepsql anti-patterns` | Schema-level (default) or query-level (`--kind query`) anti-patterns flagged by the brain. | `deepsql anti-patterns --kind query --limit 20` |
| `deepsql indexes <subcommand>` | Read-only index intelligence: `list`, `missing`, `unused`, `duplicates`, `health`, `usage <table>`. | `deepsql indexes missing --connection prod-postgres` |
| `deepsql index-recommendations <subcommand>` | Workload-weighted index advisor: `top`, `list`, `show`, `refresh`, `apply`, `dismiss`. Use `apply --mode dry-run` first to validate with HypoPG before real DDL. | `deepsql index-recommendations top --connection prod-postgres --limit 5` |
| `deepsql slow-queries <subcommand>` | Read, trigger, and stream slow-query analyses. `optimize --query-id <id>` streams AI optimization step-by-step over SSE. | `deepsql slow-queries latest --connection prod-postgres` |
| `deepsql digest [N]` | Show the most recent daily digest (anomalies, top movers, AI commentary). Pass a number to list the last N. | `deepsql digest 7` |
| `deepsql users <subcommand>` | Admin: list/get/add/set-role/lock/unlock/disable/delete workspace users. | `deepsql users list` |
| `deepsql access <subcommand>` | Admin: per-connection access grants. `list`, `grant`, `revoke`, `policy`. | `deepsql access grant --user jane@acme.com --connection prod-postgres --level write` |
| `deepsql permissions <subcommand>` | Admin: global role-based permission overrides. `list`, `override`, `reset`. | `deepsql permissions list --role DEVELOPER` |
| `deepsql setup` | Post-install wizard: SMTP/email + Slack (digests + bot), then mark setup complete. | `deepsql setup --skip-slack` |
| `deepsql mcp` | Run the stdio MCP server with the saved token (used by editor configs — you rarely run this yourself). | `deepsql mcp` |
| `deepsql mcp config --install --for <editor>` | Install DeepSQL into an editor's MCP config and the DBA-consult skill. Editors: `claude-code`, `claude-desktop`, `cursor`, `codex`. | `deepsql mcp config --install --for claude-code` |

### Global flags

| Flag | Purpose |
|---|---|
| `--url <url>` | Target a non-default DeepSQL profile. |
| `--token <tok>` | Bypass the saved profile (also: `DEEPSQL_AUTH_TOKEN` env). |
| `--connection <name>` | Connection to target (also: `DEEPSQL_CONNECTION` env). |
| `--caller-agent <id>` | Identify the calling agent in audit logs (also: `DEEPSQL_CALLER_AGENT` env). |
| `--json` | Machine-readable JSON output (where supported). |
| `--no-color` | Disable ANSI colors in output. |
| `-h, --help` | Per-command usage. e.g. `deepsql query --help` |
| `-v, --version` | Show CLI version. |

---

## MCP tools your AI agent can call

Once you've run `deepsql mcp config --install --for <editor>`, the agent in that editor has DeepSQL tools available — **41 of them as of 0.19.0**. Connection-scoped tools take a `connectionId` (UUID) you obtain once from `list_connections`. Every call runs through the same policy gate as the web SQL Editor and is audited to the security event log.

The MCP surface mirrors the `deepsql` CLI for almost every read/diagnostic operation. The exceptions are listed in *"What's CLI-first"* below.

### Identity + connections (5)

| Tool | What it does |
|---|---|
| `list_connections` | List databases this user can see. **Always call first** — every other tool needs the UUID. |
| `get_current_user` | Authenticated user, role, and bound DeepSQL host. Use to know whether the caller is admin-capable before suggesting DDL. |
| `show_connection` | One connection's saved config with all secret fields masked as `(set)`. |
| `test_connection` | Run the privilege report (+ SSH check) using the saved encrypted credentials. No plaintext crosses the agent's wire. |
| `reinit_connection_brain` | Trigger a fresh schema scan + brain re-embedding (use after the user reports stale schema knowledge). |

### Schema + retrieval brain (5)

| Tool | What it does |
|---|---|
| `get_schema` | Fetch cached schema (tables, columns, FKs, types). Fast and cheap — call freely. |
| `get_database_objects` | Tables, views, functions, procedures. Use when you need DDL-level objects, not just columns. |
| `get_brain_context` | **Primary retrieval tool.** Returns the tables, columns, FKs, business rules, and anti-patterns most relevant to your question. **Call before generating any non-trivial SQL or DDL.** |
| `list_business_rules` | Active business rules and SQL guardrails. Honor these — they encode domain semantics (e.g. `always_filter_cancelled`). |
| `get_relationships` | Inferred + validated foreign keys with confidence scores. Many real-world DBs lack declared FKs; this fills the gap. |

### Anti-patterns + daily digest (4)

| Tool | What it does |
|---|---|
| `get_anti_patterns` | `kind="table"` returns schema-level smells; `kind="query"` returns query-level smells (with optional `limit`). |
| `get_latest_digest` | Most recent DeepSQL daily digest (slow queries + AI commentary). |
| `list_digests` | Recent digest metadata — find a specific date. |
| `get_digest_by_id` | Full digest body for a single id. |

### Index advisor — workload-weighted + apply tool (5)

| Tool | What it does |
|---|---|
| `get_index_recommendations` | Top workload-weighted index recommendations with evidence, contributing query fingerprints, write-cost, expected benefit. **Call before proposing `CREATE INDEX` or `DROP INDEX`.** |
| `apply_index_recommendation` | Dry-run or apply one recommendation. Default mode is `DRY_RUN` (HypoPG-based, no writes). `APPLY` and `APPLY_AND_MEASURE` require `confirm:true`. |
| `list_index_recommendations` | Browse the full recommendation history by status (PENDING/APPLIED/DISMISSED). |
| `refresh_index_recommendations` | Force a fresh accumulation cycle (skip the 6-hour scheduler wait). |
| `dismiss_index_recommendation` | Mark a recommendation as DISMISSED after the user explicitly rejects it. |

### Index catalog diagnostics — live probes (5)

| Tool | What it does |
|---|---|
| `get_missing_indexes` | Schema-walk view of suspected missing indexes (complements `get_index_recommendations`). |
| `get_index_health` | Total/bloated/unused/duplicate/biggest indexes — first read on "audit my indexes". |
| `get_unused_indexes` | Indexes with zero/near-zero scans (drop candidates). |
| `get_duplicate_indexes` | Redundant prefix-duplicate indexes grouped by table. |
| `get_table_index_usage` | Per-table scan/read/fetch counts on every index. |

### Slow queries (9)

| Tool | What it does |
|---|---|
| `analyze_slow_queries` | Recent slow queries with fingerprints, durations, example statements. Triggers fresh collection. |
| `get_latest_slow_query_analysis` | Read the most recent persisted analysis (faster — no new work). |
| `list_slow_query_history` | Past analysis runs (compact metadata). |
| `get_slow_query_timeline` | Day-by-day timeline for one fingerprint from the 30-day store. |
| `get_query_regressions` | Slow queries that regressed on the latest daily run. |
| `list_tracked_queries` | All fingerprints tracked in the 30-day store. |
| `get_slow_query_customers` | Tenants ranked by total slow-query load. |
| `get_query_samples` | Literal SQL samples (with bind values) for one fingerprint. |
| `get_slow_query_insights` | Pre-computed AI insights — hotspots / remediation / tail-risk / plan-drift / skew. |
| `optimize_slow_query` | AI optimization recommendations (index DDL + query rewrites) for one fingerprint. |

### Growth analytics (5)

| Tool | What it does |
|---|---|
| `get_table_growth` | Per-table size/row growth from persistent stats history. |
| `get_growth_anomalies` | DeepSQL-flagged sudden growth spikes with severity and root-cause hints. |
| `acknowledge_growth_anomaly` | Mark an anomaly as expected (silences `unacknowledgedOnly` queries). |
| `get_growth_config` | Current alert thresholds and detection sensitivity. |
| `set_growth_config` | Update alert thresholds (admin-gated server-side). |

### Plan + execute — the write-capable pair (2)

| Tool | What it does |
|---|---|
| `execute_sql` | Run any single SQL statement. Developers get SELECT/WITH/SHOW/EXPLAIN; admins also get DML/DDL with a two-step confirmation (DROP is blocked). |
| `analyze_query_plan` | AI-enriched plan analysis: parsed plan tree, performance issues, index recommendations, written summary using your schema + business rules. `useAnalyze: true` actually executes the query (EXPLAIN ANALYZE). |

### What's CLI-first

A handful of capabilities are intentionally NOT MCP-exposed:

| Capability | Why CLI-only | CLI command |
|---|---|---|
| Connection write ops (add/update/remove) | Take plaintext DB credentials. We don't want passwords landing in agent conversation history. | `deepsql connections add\|update\|remove` |
| Auth flows | Interactive: browser PKCE callback, $EDITOR for chat policies, password prompts | `deepsql login\|logout`, `deepsql setup` |
| MCP installer itself | Touches the editor's config file — agent shouldn't self-modify its host | `deepsql mcp config --install --for <editor>` |
| Streaming AI optimization | SSE event stream; MCP's JSON-RPC tool model can't consume events well | `deepsql slow-queries optimize --query-id <id>` |
| Per-user admin ops | Out-of-band workflow, not session-time diagnostics | `deepsql users\|access\|permissions` |

These are reachable from any terminal where `deepsql` is installed and logged in; the saved profile is shared with the MCP server.

---

## The policy gate: how mutations are protected

DeepSQL's CLI and MCP `execute_sql` use **the same `QueryExecutionPolicyService` the web SQL Editor uses**. The rules:

1. **Developers** can run read-only SQL: `SELECT`, `WITH … SELECT`, `SHOW`, `EXPLAIN`. Anything else is rejected immediately.
2. **Admins** can additionally run `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPSERT`, plus `CREATE`, `ALTER`, `TRUNCATE`.
3. `DROP` is **blocked from CLI/MCP/Editor**. Use your database's admin tooling for schema removal.
4. `UPDATE`/`DELETE` **must include a `WHERE` clause** — unbounded mutations are rejected.
5. Multi-statement input (e.g. `UPDATE a; UPDATE b;`) is rejected. Use CTEs or run statements separately.
6. Mutations use a **two-step confirmation**. The first call returns:
   ```json
   { "requiresConfirmation": true, "warnings": ["…"], "queryType": "UPDATE" }
   ```
   You re-call with `confirmMutation: true` (CLI: `--write`) to actually execute. The two-step exists so a human always sees the warnings before the statement runs.

The database user behind the connection must *also* have write privileges. The policy gate prevents unsafe DeepSQL submissions; the database itself enforces actual write access.

---

## Common workflows

Copy-paste recipes. All assume you've already run `deepsql login` and pinned a connection.

### 0. First five minutes on a new machine

```bash
npm install -g @deepsql/mcp@latest
deepsql login --url https://deepsql.acme.com
deepsql connections list
deepsql connections use prod-postgres
deepsql query "SELECT 1 AS ok" --connection prod-postgres
```

This proves four things quickly: the package is installed, auth works, the user can see the intended connection, and the query policy gate is reachable.

### 1. Ask a question against your data (CLI)

```bash
# 1. Get the brain's view of the question — surfaces relevant tables,
#    columns, FKs, and business rules.
deepsql brain-context "monthly revenue by plan for the last 90 days" \
  --connection prod-postgres

# 2. Generate SQL using only the tables/columns the brain surfaced,
#    then run it through the policy gate.
deepsql query "
  SELECT plan_id,
         date_trunc('month', created_at) AS month,
         sum(amount_cents) / 100.0 AS revenue_usd
  FROM payments
  WHERE created_at > now() - interval '90 days'
  GROUP BY 1, 2
  ORDER BY 2
" --connection prod-postgres
```

### 2. Answer a BI-style question from the terminal

Use this pattern when a non-technical question needs a concrete SQL-backed answer, but you want DeepSQL to ground the query first.

```bash
deepsql brain-context "weekly EMEA pipeline coverage by segment" \
  --connection prod-postgres

deepsql query "
  SELECT segment,
         date_trunc('week', snapshot_at) AS week,
         round(sum(pipeline_amount) / nullif(sum(quota_amount), 0), 2) AS coverage
  FROM sales_pipeline_snapshots
  WHERE region = 'EMEA'
    AND snapshot_at >= now() - interval '14 days'
  GROUP BY 1, 2
  ORDER BY 2 DESC, coverage DESC
" --connection prod-postgres --limit 50
```

### 3. Have your AI editor consult the DBA before a migration (MCP)

With the MCP installed and the DBA-consult skill active, ask the agent something like:

```
@deepsql I want to add a "tracked when a subscription was cancelled" column.
Check the brain before suggesting schema.
```

The skill kicks in. The agent will call, in sequence:

1. `get_brain_context` — "subscription cancellation"
2. `get_schema` — to verify exact column names + types on the surfaced tables
3. `list_business_rules` — to check naming, nullability, default conventions
4. `get_anti_patterns` (kind=`table`) — to avoid shape mistakes the team has burned on before

The agent then **narrates what it found** before proposing schema — e.g. *"You already have `subscriptions.status` plus a `subscription_status_history` table tracking transitions; adding a `cancelled_at` column would create a third source of truth. I'd extend `subscription_status_history` instead. Want me to draft the migration?"*

This consult takes 1–3 tool calls and saves the developer months of cleanup. **Make it a reflex.**

### 4. Agent-safe MCP checklist

When an AI editor is using DeepSQL tools, ask it to follow this sequence:

1. Call `list_connections` and choose the intended environment explicitly.
2. Call `get_brain_context` for the human's task before writing non-trivial SQL or DDL.
3. Call `get_schema`, `get_relationships`, and `list_business_rules` for the surfaced tables.
4. For performance work, call `analyze_query_plan` or `get_index_recommendations` before suggesting an index.
5. For index changes, run `apply_index_recommendation` in `DRY_RUN` first.
6. For mutations, surface warnings to the human and require explicit approval before `confirmMutation`, `confirm:true`, or CLI `--write`.

Pasteable prompt:

```text
Use DeepSQL before proposing schema or query changes.

1. Call list_connections and choose prod-postgres.
2. Call get_brain_context for the task.
3. Verify exact tables with get_schema and get_relationships.
4. Check list_business_rules.
5. Explain what DeepSQL found before suggesting SQL.
6. Do not execute DDL or DML unless I explicitly approve.
```

### 5. Analyze why a query is slow

```bash
# Plan-only — no execution, no impact on the database
deepsql analyze "SELECT * FROM orders WHERE status='OPEN' AND tenant_id=$1" \
  --connection prod-postgres

# Real EXPLAIN ANALYZE — executes the query.
# SELECT is fine; mutations need admin role + WHERE clause + confirm.
deepsql analyze "SELECT * FROM orders WHERE status='OPEN' AND tenant_id=$1" \
  --analyze --connection prod-postgres
```

The output includes the parsed plan tree, performance issues (full table scans, bad estimates, missing indexes), index recommendations, and a written summary that takes your schema and business rules into account.

### 6. Find the safest index fix for checkout latency

Move from symptom to evidence to dry-run validation before creating anything in production.

```bash
deepsql slow-queries latest --connection prod-postgres

deepsql analyze "
  SELECT *
  FROM orders
  WHERE workspace_id = $1
    AND status = 'PENDING'
  ORDER BY updated_at DESC
  LIMIT 50
" --connection prod-postgres

deepsql index-recommendations top --connection prod-postgres --limit 5
deepsql index-recommendations show <recommendation-id> --connection prod-postgres
deepsql index-recommendations apply <recommendation-id> --mode dry-run

# After reviewing dry-run evidence and getting approval:
deepsql index-recommendations apply <recommendation-id> --mode apply --confirm
```

### 7. Find what to fix this week

```bash
deepsql indexes missing       # advisor's top missing-index picks
deepsql indexes unused        # indexes the engine isn't using
deepsql indexes duplicates    # redundant indexes you can drop
deepsql indexes health        # rolled-up score with reasons
deepsql index-recommendations top --connection prod-postgres --limit 10
deepsql digest 7              # the last 7 daily digests
```

### 8. Run a write (admin only, two-step)

```bash
# 1. First call surfaces warnings (no execution yet)
deepsql query "UPDATE orders SET status='CLOSED' WHERE id=42" \
  --connection prod-postgres

# 2. Review the warnings, then re-run with --write to confirm
deepsql query "UPDATE orders SET status='CLOSED' WHERE id=42" \
  --connection prod-postgres --write
```

The same flow applies in the MCP: first call returns `requiresConfirmation: true`, the human reviews the warnings, then the agent re-calls with `confirmMutation: true`.

### 9. Stream AI optimization for a single slow query

```bash
deepsql slow-queries latest --connection prod-postgres
# pick a query-id from the output, then:
deepsql slow-queries optimize --connection prod-postgres --query-id <id>
```

The CLI follows the SSE stream — each optimization step lands on stderr as it runs, the final recommended plan + SQL lands on stdout. Honors `SIGINT` (Ctrl-C) cleanly.

---

## Security model in one paragraph

The saved token is scoped to one DeepSQL profile and stored in `~/.config/deepsql/auth.json` with mode `0600`. The MCP server **never** prints or transmits the token to the editor; it reads it directly from the file each time the editor launches the server process. Every CLI and MCP call is logged to `security_events` with the user's identity (from the bearer token), the editor that invoked the MCP (`claude-desktop`, `cursor-mcp`, `codex-mcp` — set via `DEEPSQL_MCP_USER_ID` in the editor's config), the connection ID, statement hash + truncated text, outcome, and whether mutation confirmation was required and given. Workspace admins can search this from the **Security** tab in the UI.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `deepsql: command not found` | npm global bin not on PATH | Add `$(npm prefix -g)/bin` to your PATH, or `npm install -g @deepsql/mcp` again to surface the warning |
| `deepsql login` opens a browser but never returns | Firewall blocks the local callback port | Re-run with `--device` for the device-code flow |
| `403 EDITOR_MUTATION_FORBIDDEN` from `execute_sql` | Your DeepSQL role is `DEVELOPER` and you tried to run DML/DDL | Ask a workspace admin to either grant write access on that connection or run the change for you |
| `requiresConfirmation: true` from `execute_sql` | The statement is a mutation that needs admin confirmation | Surface the `warnings` array to the human, get explicit OK, re-call with `confirmMutation: true` |
| `EDITOR_MUTATION_BLOCKED` with `queryType: "DROP"` | `DROP` is intentionally blocked from CLI/MCP/Editor | Use your database's admin tooling (`psql`, `mysql`, RDS console, etc.) |
| `UNSAFE_MUTATION_BLOCKED` mentioning "WHERE clause" | UPDATE/DELETE without a `WHERE` | Add a `WHERE` clause that scopes the affected rows |
| `Connection not found` | The `connectionId` doesn't belong to this user, or you typo'd the UUID | `list_connections` again — connection IDs change after delete + recreate |
| The MCP server isn't reachable in the editor | Editor wasn't restarted after install | Quit and reopen the editor. On Cursor, you may also need to toggle MCP off and on in Settings |
| Tool calls error with `Saved token not found` | You ran `deepsql logout` after installing the MCP entry | Run `deepsql login` again — the same token file is reused, no need to re-install the MCP config |

---

## Where to go next

- **Web UI** — `https://your-deepsql-host` → **Brain** tab. Same brain context, same business rules, same anti-patterns — visual instead of CLI.
- **Self-host install guide** — see `docs/root/MCP_PHASE1.md` in the repository.
- **MCP spec** — https://modelcontextprotocol.io
- **Anthropic agent SDK** — for building your own client around these tools.

The DeepSQL brain is the same whether you reach it through the web app, the CLI, or your editor's MCP. Pick whichever fits the task; switching costs you nothing.
