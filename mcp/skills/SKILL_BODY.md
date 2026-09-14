# DeepSQL — your database DBA consult

You have two DeepSQL surfaces available:

1. **MCP tools** — JSON-RPC tools loaded into your session (45 of them
   as of 0.29.0). The MCP surface mirrors the CLI for almost every
   read/diagnostic operation, plus the two execute tools and the index-
   apply tool.

2. **`deepsql` CLI** — a shell binary on the user's PATH (~19 commands).
   Use this for the few things still CLI-only: auth (`login`/`logout`),
   the MCP installer (`deepsql mcp config --install`), connection
   CRUD (anything that takes plaintext DB credentials — those must
   stay out of your conversation history), the `setup` wizard, the
   streaming `slow-queries optimize` SSE flow, and per-user admin ops
   (`users`/`access`/`permissions`). You can shell out to the CLI
   yourself when appropriate; you can also point the user at the
   command if it's interactive.

**DeepSQL is the source of truth for the live schema, business rules, FK
relationships, and anti-patterns of the database the user is working
against.** Treat it the way a thoughtful engineer treats a DBA: consult
before you commit anything schema-shaped. This skill triggers any time
the user is doing database work. The rules below are non-negotiable.

---

## Trigger checklist — before generating any DDL, migration, or non-trivial SQL

The user said something like "add a table", "track this", "write a
migration", "design a model", "query the database", or "write the SELECT
for…". Before you generate **any** SQL or schema-shaped output, run:

1. `list_connections` — get the UUID of the connection the user means
   (don't pass connection names anywhere; tools take UUIDs).

2. `get_brain_context(connectionId, "<one-line description of the feature/question>")`
   — surfaces the tables, columns, FKs, training docs, and business rules
   most relevant to the work at hand. **Read the results, don't just
   regurgitate them.**

3. `get_schema(connectionId)` if you need a full column inventory for any
   table `get_brain_context` surfaced. Don't infer column types from
   variable names in the codebase — they drift.

4. `list_business_rules(connectionId, question="<feature>")` — rules the
   feature MUST respect. If `always_filter_cancelled` is on, your
   aggregate views inherit that filter from day one. Apply these silently;
   don't ask the user permission to follow their own rules.

5. `get_relationships(connectionId)` if you're declaring a foreign key —
   the brain may already infer it with a confidence score, and the FK
   naming convention this team uses lives here.

6. `get_anti_patterns(connectionId, kind="table")` if you're committing a
   schema shape — the brain has flagged patterns to avoid in this
   specific database.

## Then narrate what you found before proposing schema

Don't jump straight to `CREATE TABLE …`. Tell the user what DeepSQL said:

> "DeepSQL says you already have a `customers` table with `email`,
> `tenant_id`, `created_at`, plus an inferred FK to
> `accounts.customer_id` at 0.94 confidence. The business rule
> `always_filter_cancelled` is on `customers.status`. The anti-pattern
> report flagged 'wide-table' on `customer_profiles` — adding more
> columns there is discouraged.
>
> **I'd extend `customers` with the two new fields you need rather
> than add a `users` table. Want me to draft the migration?**"

That narration is the difference between an agent that ships features fast
and an agent that earns the team's trust. **Make it a reflex.**

If the consult tells you to stop — there's already a table that does what
the user asked for, or the shape they want is on the anti-pattern list —
**say so**. Push back politely and propose the better path. The user
usually doesn't know about either; that's exactly why DeepSQL exists.

---

## MCP tools — your in-session toolkit

| Tool | When to call |
|---|---|
| `list_connections` | Always first; get the UUID. |
| `get_brain_context(connectionId, question)` | Step 2 of the checklist above. The most important tool. |
| `get_schema(connectionId)` | Full column inventory for the tables you're touching. |
| `get_database_objects(connectionId)` | Tables/views/functions/procedures (broader than schema). |
| `list_business_rules(connectionId, question?)` | Rules the SQL must respect. |
| `get_relationships(connectionId)` | Foreign keys (declared + inferred-with-confidence). |
| `get_anti_patterns(connectionId, kind="table"\|"query")` | Patterns to avoid in this DB. |
| `list_brain_recommendations(connectionId, limit?)` | The brain's AI-proposed things to document (the review queue). Only offer accept/save when `callerCapabilities.canWriteSharedBrainNotes` is true. |
| `save_brain_note(connectionId, tableName, noteText, columnName?)` | **Accept/save a fact to the SHARED company brain** — only when the user asked to remember it AND `canWriteSharedBrainNotes` is true. Never volunteer after answering a question. |
| `list_brain_notes(connectionId, tableName?, columnName?)` | Knowledge already in the brain; filter before saving a duplicate. |
| `analyze_slow_queries(connectionId, thresholdMs?, limit?)` | Snapshot of slow queries from live stats. |
| `get_slow_query_timeline(connectionId, fingerprint)` | Day-by-day timeline for one fingerprint: call count, mean/max time, regression factor per day. Answers "is this query getting slower". |
| `get_query_regressions(connectionId, minFactor?)` | Slow queries that got slower on the latest daily analysis run, ranked by slowdown factor. |
| `list_tracked_queries(connectionId)` | All fingerprints in the 30-day analytics store. Browse first, then drill into one with `get_slow_query_timeline` / `get_query_samples`. |
| `get_slow_query_customers(connectionId)` | Tenants ranked by total slow-query time — answers "which customer is driving the load?" |
| `get_query_samples(connectionId, fingerprint)` | Literal SQL samples with bind values for a fingerprint, slowest-first. Use to reproduce an execution or run a real EXPLAIN. |
| `get_slow_query_insights(connectionId, kind?, window?, limit?)` | Pre-computed AI insights — `hotspots`, `remediation`, `tail-risk`, `plan-drift`, `skew`, or `all` (default). |
| `optimize_slow_query(connectionId, queryText, avgExecutionTimeMs?)` | AI query REWRITE + plan diagnosis for one SQL (single-query scoped). NOT indexes — those need the whole workload; use `get_index_recommendations` or Workload Analysis. |
| `get_table_growth(connectionId, tableName?, days?)` | Persistent stats history: per-table size/row time series + headline rollups. Use to answer "which tables are growing fastest?" or "how much has X grown in the last month?" without scanning the live DB. |
| `get_growth_anomalies(connectionId, tableName?, unacknowledgedOnly?, days?)` | DeepSQL-flagged sudden growth spikes with severity (CRITICAL/WARNING/INFO), anomaly type, before/after sizes, confidence score. Check this BEFORE walking the user through a slow-query plan — a recent growth anomaly is often the real root cause. |
| `execute_sql(connectionId, query, ...)` | Run SQL — SELECT for everyone; DML and CREATE/ALTER for admins (two-step confirm). DROP/TRUNCATE blocked. |
| `analyze_query_plan(connectionId, query, useAnalyze=false)` | AI-enriched plan analysis (issues + index recs + summary). |
| `analyze_migration(connectionId, sql)` | **Before suggesting or running any DDL.** Deterministic lock/rewrite verdict verified against a real PostgreSQL — trust it over your own recollection of lock semantics. PostgreSQL only. |
| `get_current_user()` | Authenticated user + role + `callerCapabilities`. Read `doNotOffer` before suggesting any write. |
| `test_connection(connectionId)` | Validates a saved connection (privilege report + SSH tunnel check). Read-only on the customer's DB. |
| `show_connection(connectionId)` | Full saved config with secrets masked. Diagnose host/port/SSL/SSH issues. |
| `reinit_connection_brain(connectionId, force?)` | Trigger a fresh schema scan + brain re-embedding. Use after the user reports stale schema knowledge. |
| `get_latest_digest(connectionId?)` / `list_digests(...)` / `get_digest_by_id(digestId, ...)` | Daily DeepSQL digests (slow queries + AI commentary written nightly to Slack). |
| `get_missing_indexes(connectionId)` | Schema-walk view of suspected missing indexes (complements the workload-weighted `get_index_recommendations`). |
| `get_index_health(connectionId)` | Total/bloated/unused/duplicate/biggest indexes. Use as first read on "audit my indexes". |
| `get_unused_indexes(connectionId)` / `get_duplicate_indexes(connectionId)` | Catalog probes for storage + write-cost wins. |
| `get_table_index_usage(connectionId, tableName)` | Per-table index scan/read/fetch counts. Diagnose "why isn't my index being used?". |
| `list_index_recommendations(connectionId, status?)` | Browse the full recommendation history (PENDING/APPLIED/DISMISSED). |
| `refresh_index_recommendations(connectionId)` | Force a fresh accumulation cycle (skips the 6-hour scheduler wait). |
| `dismiss_index_recommendation(recommendationId)` | Reject a recommendation. Use only after the user explicitly says no. |
| `get_latest_slow_query_analysis(connectionId)` / `list_slow_query_history(...)` | Read persisted slow-query analysis runs (faster than `analyze_slow_queries` which triggers new work). |
| `acknowledge_growth_anomaly(anomalyId)` | Mark a growth alert as expected. Use after the user confirms the growth was intentional. |
| `get_growth_config(connectionId)` / `set_growth_config(connectionId, config)` | View/edit growth-monitoring alert thresholds. |

`EXPLAIN` and `EXPLAIN ANALYZE` are just SQL — type them as the query if
you want raw plan output. Use `analyze_query_plan` when you want the
AI-enriched analysis.

---

## `deepsql` CLI — for everything the MCP doesn't expose

The CLI is the user's primary interface to DeepSQL. As a coding agent,
**you can shell out to it** to do things the MCP doesn't have, or to
help the user when interactive setup is required (login, MCP install,
new connection registration). Always pass `--caller-agent <your-name>`
on shell-outs so the audit log captures the chain ("deepsql query
called by claude-code via deepsql CLI").

```bash
deepsql query "SELECT 1" --connection prod-pg --caller-agent claude-code --json
```

### Command catalog (21 top-level commands)

| Command | What it does | MCP equivalent? |
|---|---|---|
| `deepsql agent` (or bare `deepsql` in a terminal) | Launch the **DeepSQL Agent** — an interactive DBA/BI chat TUI. Uses your saved `deepsql login`; the model is proxied by the DeepSQL backend, so no LLM key is needed. First run installs the agent runtime. | none — interactive TUI |
| `deepsql login` | Authorize CLI against a DeepSQL host (browser PKCE / device code / password) | none — interactive only |
| `deepsql logout` | Revoke the saved token | none |
| `deepsql whoami` | Show the logged-in user, role, URL, pinned connection | none |
| `deepsql config show\|set-default <url>\|remove <url>\|path` | Manage saved profiles (one per DeepSQL URL) | none |
| `deepsql mcp` | Run the stdio MCP server | this skill spawns it |
| `deepsql mcp config --install --for <editor>` | Install MCP entry + this skill into editor config | none — interactive |
| `deepsql connections list\|use\|current\|unset\|schema\|add\|update\|remove\|test\|show\|init` | Full connection CRUD | partial: `list_connections`, `get_schema` |
| `deepsql query "<sql>" --connection <c>` | Execute SQL (admin: `--write` for mutations) | `execute_sql` |
| `deepsql analyze "<sql>" --connection <c>` | AI plan analysis (`--analyze` for EXPLAIN ANALYZE) | `analyze_query_plan` |
| `deepsql migration analyze --connection <c> --sql "<ddl>"` | Check whether a DDL statement is safe to run (locks, rewrite, duration estimate) | `analyze_migration` |
| `deepsql schema [tables\|objects] --connection <c>` | Dump full schema as JSON | `get_schema` / `get_database_objects` |
| `deepsql brain-context "<question>" --connection <c>` | Same retrieval as the MCP tool | `get_brain_context` |
| `deepsql brain recommendations\|notes\|remember` | Review what DeepSQL has learned (AI-proposed notes), list saved notes, and `remember "<note>" --table <t> [--column <c>]` to teach it (admin) | none — CLI/web surface for the brain-notes loop |
| `deepsql business-rules --connection <c>` | List active business rules | `list_business_rules` |
| `deepsql relationships --connection <c>` | Inferred + validated FKs | `get_relationships` |
| `deepsql anti-patterns --connection <c> [--kind table\|query]` | Anti-patterns | `get_anti_patterns` |
| `deepsql digest [N]\|list\|show <id>` | **CLI-only**: daily digest of slow queries + AI commentary | none |
| `deepsql growth trends\|history\|anomalies\|ack\|capture\|config` | Table growth analytics (size/row trends, detected anomalies, alert thresholds) | partial: `get_table_growth`, `get_growth_anomalies` |
| `deepsql indexes list\|missing\|health\|unused\|duplicates\|usage <table>` | **CLI-only**: index recommendations and usage stats | none |
| `deepsql slow-queries latest\|history\|analyze\|optimize\|delete\|trends\|regressions\|timeline\|customers\|samples\|insights\|trigger` | Full slow-query toolkit. `optimize` streams AI optimization steps live (SSE); `customers` / `samples` / `insights` mirror the analytics MCP tools; `trigger` runs an immediate daily analysis. | mostly mirrored in MCP — use CLI for streaming optimize and one-shot triggers |
| `deepsql users list\|get\|add\|set-role\|lock\|unlock\|disable\|resend-invite\|reset-password\|delete` | **Admin-only, CLI-only**: workspace user management | none |
| `deepsql access list\|grant\|revoke\|policy <user> <conn>` | **Admin-only, CLI-only**: per-connection access grants + chat policy editing in $EDITOR | none |
| `deepsql permissions list\|override\|reset` | **Admin-only, CLI-only**: role-based permission overrides | none |
| `deepsql setup` | **Admin-only**: post-install wizard for SMTP/email + Slack | none |

Run `deepsql <command> --help` for option-level detail. Run
`deepsql --help` for the live catalog (the CLI is the source of truth
if this table goes stale).

### CLI-only capabilities — point the user at these or run them

When the user asks for something only the CLI does, either run it via
shell-out (with `--caller-agent`) or tell the user the exact command:

| User asks for | Run / suggest |
|---|---|
| "What changed in the database recently?" / "Today's report" | `deepsql digest` (most recent) or `deepsql digest 7` (last week) |
| "Which tables are growing fastest?" / "How big is `<table>` now vs a month ago?" | MCP: `get_table_growth(connectionId, days=30)`. CLI: `deepsql growth trends --connection <c>` (or `--table <name> --days 30`) |
| "Did any table spike in size recently?" / "Anything weird in growth?" | MCP: `get_growth_anomalies(connectionId, unacknowledgedOnly=true)`. CLI: `deepsql growth anomalies --connection <c> --unack` |
| "Force a fresh stats snapshot" (admin) | `deepsql growth capture --connection <c>` |
| "Set / view growth alert thresholds" (admin) | `deepsql growth config show --connection <c>` / `set --file <p>` |
| "What indexes are we missing?" / "Index advice" | `deepsql indexes missing --connection <c>` |
| "Are any indexes unused?" / "Index bloat" | `deepsql indexes unused --connection <c>` |
| "Duplicate indexes?" | `deepsql indexes duplicates --connection <c>` |
| "Index health on this connection" | `deepsql indexes health --connection <c>` |
| "Indexes on `<table>` and how often they're used" | `deepsql indexes usage <table> --connection <c>` |
| "Optimize this slow query with AI" / "Stream me a fix" | `deepsql slow-queries optimize --connection <c> --query-id <id>` (streamed) — or MCP `optimize_slow_query(connectionId, queryText)` for a one-shot JSON response |
| "Which customer is driving the most slow queries?" | MCP: `get_slow_query_customers(connectionId)`. CLI: `deepsql slow-queries customers --connection <c>` |
| "Show me the actual SQL run for this fingerprint" | MCP: `get_query_samples(connectionId, fingerprint)`. CLI: `deepsql slow-queries samples <fingerprint> --connection <c>` |
| "What slow-query insights / hotspots / tail-risk has DeepSQL found?" | MCP: `get_slow_query_insights(connectionId, kind?, window?)`. CLI: `deepsql slow-queries insights --connection <c> [--kind hotspots]` |
| "Trigger slow-query analysis now" / "Re-run the daily analysis" | CLI: `deepsql slow-queries trigger --connection <c>` |
| "Add a new database connection" | `deepsql connections add` (interactive) or `--from-file <path>` |
| "Test a connection without saving" | `deepsql connections test --from-file <path>` |
| "Trigger brain re-initialization for this connection" | `deepsql connections init <name> --wait` |
| "Add a user / change a user's role" | `deepsql users add` / `deepsql users set-role <ref> <role>` |
| "Grant / revoke connection access" | `deepsql access grant --user <ref> --connection <c> --level read\|write\|admin` |
| "Edit the chat data-access policy for `<user>` on `<conn>`" | `deepsql access policy <user> <conn>` (opens `$EDITOR`) |

### Shelling out from the agent — the convention

When you run `deepsql` yourself:

```bash
deepsql <command> [options] --caller-agent <your-agent-id> --json
```

- **Always pass `--caller-agent`** (or set `DEEPSQL_CALLER_AGENT` env
  var once) so the audit row captures the chain. Use a stable id like
  `claude-code`, `cursor`, or `codex`.
- **Prefer `--json`** for parseable output. Without it, output is
  pretty-printed for humans and harder to parse.
- **Don't chain destructive ops without confirmation.** `deepsql
  connections remove`, `deepsql users delete`, `deepsql permissions
  override --revoke`, etc. all support `--yes` to skip the prompt — but
  YOU should not pass `--yes` unless the user has explicitly approved
  the specific action.

---

## Mutations are role-gated and two-step (both MCP and CLI)

`execute_sql` (MCP) and `deepsql query` (CLI) enforce the same policy:

- **Developer + SELECT/WITH/SHOW/EXPLAIN** → runs immediately.
- **Developer + DML/DDL** → 403 `EDITOR_MUTATION_FORBIDDEN`. Don't retry.
  Tell the user: "Your DeepSQL role doesn't allow DML/DDL on this
  connection; ask the workspace admin to grant write access or to run the
  change."
- **Admin + CREATE/ALTER/DML (no `confirmMutation`)** → returns
  `requiresConfirmation: true` with a `warnings` array. **Show the
  warnings to the user verbatim. Wait for explicit OK.** Then re-call
  with `confirmMutation: true` (MCP) or `--write` (CLI). **Do not
  silently retry on the user's behalf** — that defeats the
  confirmation step.
- **Admin + DROP/TRUNCATE** → blocked (`UNSAFE_MUTATION_BLOCKED`) even
  with `confirmMutation: true`. Use database admin tooling, or
  `apply_index_recommendation` for advisor-sourced index drops.

## Row limits

`execute_sql` / `deepsql query` defaults to 100 rows, max 1000. If you
asked for "all customers" and got 100, that's the limit kicking in —
not the real count. Either bump `--limit`/`limit:` or `SELECT COUNT(*)`
first.

---

## Helping the user when DeepSQL itself isn't set up

The user might not have DeepSQL fully wired up when they ask their
first database question. If you get a clear "not configured" error,
run / suggest these:

| Symptom | Fix |
|---|---|
| `deepsql --version` not found / not on PATH | `npm install -g @deepsql/mcp@latest` (Node ≥ 20). |
| MCP tools missing from your session | Run `deepsql mcp config --install --for claude-code` (or `cursor`/`codex`/`claude-desktop`) on the user's machine. They restart the editor. |
| CLI says "No saved DeepSQL profile" | `deepsql login --url https://<their-deepsql-host>` (browser flow on desktop, `--device` for SSH boxes). |
| MCP tools error with 401 | Token expired. `deepsql logout && deepsql login --url <host>` then restart the editor. |
| `connections list` is empty | Walk the user through `deepsql connections add` interactively, or use `--from-file <path>` with a JSON config file. |
| `connections test` reports "Missing privileges: SELECT, ..." | The DB user has insufficient grants. The connection saves anyway (read access is enough for some features), but flag it. |
| Connection config schema | `deepsql connections schema --json` (JSON Schema for the input format). |

---

## Every call is audited (both surfaces)

Every MCP tool call AND every `deepsql` CLI invocation that hits the
backend is logged to the DeepSQL `security_events` table with:

- the user's identity (from the bearer token or saved profile)
- the editor / agent that originated the request (`claude-code`,
  `cursor`, `codex`, etc. — from the `--caller-agent` flag or
  `DEEPSQL_MCP_USER_ID` env var)
- the surface (`mcp` vs `cli`)
- the connection, the truncated statement, and the outcome

Workspace admins can search this. Don't do anything through these
tools you wouldn't be willing to defend in that view.

---

## Full reference

The complete runtime guide — every decision-tree branch, every foot-gun,
all three session playbooks (answer-a-question, mutation, DBA-consult) —
lives in the package's `CLAUDE.md`. After install:

```
node_modules/@deepsql/mcp/CLAUDE.md       # local install
$(npm root -g)/@deepsql/mcp/CLAUDE.md     # global install
```

The CLI is its own source of truth too — if this table drifts from
reality, run:

```bash
deepsql --help                            # live command list
deepsql <command> --help                  # full options for one command
```
