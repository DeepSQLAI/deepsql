# DeepSQL — runtime guidance for AI agents

> You are an AI agent (Claude Code, Cursor, Codex, etc.) with DeepSQL's MCP
> tools loaded. This file tells you how to use them well. Read once; the
> patterns here will save you and your user real pain.

DeepSQL is a self-hosted AI database performance assistant. It indexes the
user's schemas into a retrieval brain, audits query workloads, and exposes
two SQL surfaces through MCP plus a `deepsql` CLI.

You'll use it in two distinct modes, and you need to recognize which one
you're in:

1. **Answer a question about the data.** The user asked something like
   "how many orders did we ship last week?" You write SQL, run it, return
   the answer. The "decision tree" below covers this flow.

2. **Build a feature against an existing database.** The user is in their
   codebase asking you to add a table, add a column, write a migration,
   model a new relationship, design a query for the new ORM model, etc.
   **This is where most agents fail silently** — they generate plausible
   schema without consulting the existing one, and the developer ends up
   with `users` next to `customers` and `cancelled_at` next to a
   `status_history` table that already tracks the same thing. The "DBA
   consult" section below covers this flow. **Read it before you generate
   any DDL.**

**Every call you make runs through the same policy gate as the web SQL
Editor and is logged with your identity, the editor that invoked the MCP
server, and the statement you ran.** Don't be sloppy.

---

## The tools you have

The MCP server exposes **44 tools** as of 0.26.0 — the original 16 +
0.18.x's 5 slow-query tools + 0.19.0's 20 CLI-parity additions + 0.26.0's
3 brain-notes tools (`list_brain_recommendations`, `save_brain_note`,
`list_brain_notes`). Most
take a `connectionId` (UUID returned by `list_connections`); a few
take server-resolved row ids (`apply_index_recommendation` →
`recommendationId`, `dismiss_index_recommendation` →
`recommendationId`, `acknowledge_growth_anomaly` → `anomalyId`,
`get_digest_by_id` → `digestId`); `get_current_user` and
`list_connections` take no args.

The surface mirrors the `deepsql` CLI for almost every read/diagnostic
operation. **Three CLI capabilities are intentionally NOT in MCP**:

1. **Connection write ops** (`add`, `update`, `remove`) — they take
   plaintext DB credentials, which would land in your conversation
   history. Tell the user to run `deepsql connections add` at a TTY.
2. **Auth flows** (`login`, `logout`, `setup`, `mcp config --install`) —
   interactive by nature.
3. **Streaming AI optimization** (`slow-queries optimize`) — SSE
   stream, doesn't fit JSON-RPC tools.

Per-user admin ops (`users`, `access`, `permissions`) are also CLI-only
in this version; ask before mid-session admin work.

| Tool | Purpose |
|---|---|
| `list_connections` | List databases the user has access to. Always call this first — you need IDs for everything else. |
| `get_schema` | Cached schema metadata (tables, columns, FKs, types). Cheap and fast — call freely. |
| `get_database_objects` | Tables, views, functions, procedures. Use when you need DDL-level objects, not just columns. |
| `get_brain_context` | **Your primary retrieval tool.** Given a question, returns the tables/columns/FKs/training docs/business rules/anti-patterns most relevant to it. |
| `list_business_rules` | Active business rules and SQL guardrails. Honor these — they encode domain semantics. |
| `get_relationships` | Inferred + validated foreign keys with confidence scores. Many real DBs lack declared FKs; this fills the gap. |
| `get_anti_patterns` | Schema-level (`kind=table`) or query-level (`kind=query`) anti-patterns. |
| `list_brain_recommendations` | The brain's AI-proposed notes to review for a connection (priority, reason, indicators, suggested prompt). The company-context review queue. |
| `save_brain_note` | **Accept/save a fact into the shared brain** — grounds every future answer for the connection. TABLE- or COLUMN-scoped. Admin (manage-content), audited. Personal preferences belong in a DeepSQL skill, not here. |
| `list_brain_notes` | Knowledge already saved to the brain (filter by table/column; can be thousands). |
| `analyze_slow_queries` | Recent slow queries with fingerprints, durations, examples. Read-only; doesn't trigger new work. |
| `get_slow_query_timeline` | Day-by-day timeline for one query from the 30-day analytics store — call count, mean/max time, regression factor per day. Identify the query by its fingerprint (the `queryId` from `analyze_slow_queries`). Answers "is this query getting slower". |
| `get_query_regressions` | Slow queries that regressed (got slower) on the latest daily analysis run, ranked by slowdown factor. Read-only. |
| `list_tracked_queries` | All slow-query fingerprints in the 30-day analytics store, with call counts, mean/max exec time, and regression flags. Use to discover what's worth drilling into before pulling a timeline or samples. |
| `get_slow_query_customers` | Tenants/customers ranked by total slow-query time. Includes resolved customer name when a lookup table is configured. Answers "which customer is driving the load?" |
| `get_query_samples` | Literal SQL samples (with actual bind values substituted) for a fingerprint, slowest-first. Use to reproduce an execution, get a real EXPLAIN plan, or see how different callers use the same query shape. |
| `get_slow_query_insights` | Pre-computed AI insights for slow queries grouped by `kind`: `hotspots` (most total DB time), `remediation` (actionable fixes), `tail-risk` (p95/max outliers), `plan-drift` (execution plan changed), `skew` (one tenant disproportionately loaded). Default `all` returns the combined list. Accepts `window` (`LAST_24_HOURS` / `LAST_7_DAYS` / `LAST_30_DAYS`) and `limit`. |
| `optimize_slow_query` | AI query REWRITE + plan diagnosis for one specific SQL. Single-query scoped, synchronous. Does NOT recommend indexes — index/pre-aggregation recs require whole-workload context (`get_index_recommendations` / Workload Analysis). Pass `avgExecutionTimeMs` to anchor the impact estimate. |
| `get_index_recommendations` | **Workload-weighted DBA-grade index advisor.** Pre-computed top-N (default 5) recommendations ranked by net benefit (`Σ calls × mean_exec_time` − write-cost). Each result carries up to 5 contributing query fingerprints, the role each column played, and optional HypoPG cost-delta on Postgres. Covers both `CREATE_INDEX` and `DROP_INDEX` (unused + redundant-prefix) candidates. |
| **`apply_index_recommendation`** | **The only write-capable MCP tool.** Apply (or dry-run) a recommendation against its target connection and measure the before/after benefit on contributing queries. `DRY_RUN` (default) uses HypoPG (Postgres-only) for zero-write cost-delta. `APPLY` runs real `CREATE/DROP INDEX CONCURRENTLY` (configurable via `concurrent`). `APPLY_AND_MEASURE` additionally runs `EXPLAIN ANALYZE` for wall-clock timings. Write modes require `confirm: true`. The DDL is server-generated from the recommendation row — clients never supply SQL. |
| **`execute_sql`** | **Run any SQL statement.** Policy is server-enforced: developers can run SELECT/WITH/SHOW/EXPLAIN; admins can also run DML/DDL with a two-step confirmation. EXPLAIN and EXPLAIN ANALYZE are just SQL — no separate flag. |
| **`analyze_query_plan`** | **AI-enriched plan analysis** for a query. Returns the parsed plan tree, performance issues, index recommendations, and a written summary that takes the connection's schema and business rules into account. Pass `useAnalyze: true` to run `EXPLAIN ANALYZE` (actually executes the query). |

---

## Hard rules

1. **One execution tool, one analysis tool — that's it.** If you find yourself
   reaching for `execute_sql` to get a plan, stop. Use `analyze_query_plan`.
   If you find yourself wrapping queries in `EXPLAIN` by hand to avoid running
   them, stop. Plain EXPLAIN is read-only on every database engine; just type
   the SQL.

2. **The policy gate is real.** If `execute_sql` returns
   `{ requiresConfirmation: true, warnings: [...] }`, the statement is a
   mutation that needs admin confirmation. Show the warnings to the user,
   wait for their explicit OK, then re-call with `confirmMutation: true`.
   Do not silently retry with `confirmMutation: true` on the user's behalf —
   that defeats the whole point of the gate.

3. **Developers cannot run mutations.** If the user's token has
   `Role.DEVELOPER` and they ask you to `UPDATE users …`, the server will
   return `EDITOR_MUTATION_FORBIDDEN`. Don't try to work around it. Tell the
   user: "Your DeepSQL role doesn't allow DML/DDL on this connection; ask
   the workspace admin to grant write access or to run the change."

4. **Pass `connectionId`, not names.** Names are user-facing; tools take UUIDs.
   Get them from `list_connections` once and cache for the session.

5. **Always call `get_brain_context` before generating non-trivial SQL.** The
   brain knows about business rules, anti-patterns, and inferred FKs that
   aren't in the raw schema. Skipping it produces "technically valid,
   semantically wrong" SQL — the worst kind of failure.

6. **`execute_sql` limits matter.** Default 100 rows, max 1000. Asking for
   "all customers" and getting 100 is the limit kicking in — not the real
   count. Either bump `limit` (max 1000) or `SELECT COUNT(*)` first.

7. **Honor business rules silently.** If `list_business_rules` returns
   `always_filter_cancelled` for a connection, your `SELECT * FROM orders`
   suggestion is wrong without `WHERE status != 'CANCELLED'`. Apply the rule
   in the SQL you generate; don't ask the user permission to follow their
   own rules.

8. **Treat DeepSQL like the DBA. Consult before you commit schema.** This
   is the most important rule for feature-development work — it gets its
   own section below.

---

## Treat DeepSQL like your DBA — consult before you commit

If you're helping a developer build a feature, **the moment they say "add
a table for X," "track Y," "save Z somewhere," or "let's write a
migration" is the moment to call DeepSQL.** The brain has things the raw
codebase doesn't:

- business rules the team's previous DBA encoded
- foreign-key relationships the schema infers but doesn't declare
- anti-patterns that have already burned this team in this database
- columns and tables that exist but live on parts of the schema the
  developer hasn't seen yet

Skipping the consult is the most common way agents make a database worse:

- The developer asks for a `users` table. You create one. There's already
  a `customers` table doing 90% of the same thing, and now half the new
  feature's data lives in the wrong place forever.
- The developer asks to "track when an order was cancelled." You add a
  `cancelled_at` column. The team's existing pattern is `status` +
  `order_status_history` — your column is now an inconsistent third
  source of truth.
- The developer asks for "an index on `email`." There's already a unique
  constraint on `(tenant_id, email)` covering most lookups, and your
  single-column index is dead weight that the optimizer will rarely pick.

The consult takes one to three tool calls and saves the user months of
cleanup. **Make it a reflex.**

### The before-you-commit checklist

Run these *before* you generate any DDL, migration file, ORM model, or
schema-shaped design proposal:

1. **`get_brain_context(connectionId, "<one-line description of the feature>")`** —
   surfaces the tables, columns, FKs, training docs, and business rules
   most relevant. Read the results, don't just regurgitate them.

2. **`get_schema(connectionId)`** if you need the full column inventory
   for the tables `get_brain_context` surfaced. Confirm exact column names
   and types before you reference them.

3. **`list_business_rules(connectionId, question="<feature>")`** — active
   rules the new feature MUST respect. `always_filter_cancelled` means
   your new aggregate view inherits that filter from day one.

4. **`get_relationships(connectionId)`** if you're about to declare a new
   foreign key. The brain may already infer the relationship — and the
   inferred FK column has a confidence score telling you whether the
   convention is reliable enough to follow without explicitly declaring.

5. **`get_anti_patterns(connectionId, kind="table")`** if you're about to
   commit a schema shape (single fat table, denormalized JSON column,
   unindexed FK, …). The brain has flagged these patterns elsewhere in
   this exact database; don't add to the pile.

### Narrate what you found, then propose

When the checklist is done, **explain to the developer what you found
before you propose schema**. Example:

> "DeepSQL says you already have a `customers` table with `email`,
> `tenant_id`, `created_at`, plus an inferred FK to `accounts.customer_id`
> at 0.94 confidence. The team's business rule `always_filter_cancelled`
> is on `customers.status`. The `kind=table` anti-pattern report flagged
> 'wide-table' on `customer_profiles` — adding more columns there is
> discouraged. **I'd extend `customers` with the two new fields you need
> rather than adding a `users` table. Want me to draft the migration?**"

That explicit handoff is the difference between an agent that ships
features fast and an agent that earns the DBA's trust.

### When the consult tells you to stop

Sometimes the right answer is "don't add this." If `get_brain_context`
surfaces an existing table that already does what the user is asking for,
say so. If `get_anti_patterns` flags the shape they're about to add, say
so. The user usually doesn't know about either; that's exactly why
DeepSQL exists. Push back politely and propose the better path.

---

## The "I need to…" decision tree

**"What tables exist?"** → `get_schema` (or `list_connections` first if you
don't know which connection). Don't ask `execute_sql` to query
`information_schema` — `get_schema` is faster and cached.

**"Write a SQL query to answer X."** → `get_brain_context` with X as the
question, then generate SQL using only the columns/tables it surfaced.

**"Run this SQL and tell me what it returns."** → `execute_sql`. If you're
unsure whether the query is correct, call `analyze_query_plan` first (plain
EXPLAIN, no execution) — costs nothing and catches bad joins.

**"Why is this query slow?"** → `analyze_query_plan`. The AI summary points
at the slow nodes, missing indexes, and bad estimates. For workload-level
problems use `analyze_slow_queries`.

**"What's the real execution time for this query?"** → `analyze_query_plan`
with `useAnalyze: true`. **This actually runs the query**, so if the
statement mutates the database, the same admin role + WHERE-clause + confirm
gates that protect `execute_sql` kick in. You'll get back
`requiresConfirmation` if you forgot.

**"Apply this migration"** / **"Add this column"** / **"Delete these rows"**
→ `execute_sql` with the DDL/DML. Only admins can do it. On first call you'll
get `requiresConfirmation` — surface the warnings to the user verbatim, wait
for them to say yes, then re-call with `confirmMutation: true`.

**"What indexes should we add?"** → `get_index_recommendations`. Returns
the workload-weighted top-N (default 5) with net benefit, contributing
queries, and HypoPG cost-delta when available. The terminal equivalent
is `deepsql indexes top` (same data, same ranking); `deepsql indexes
missing` / `health` / `unused` / `duplicates` cover the catalog-level
diagnostics that complement the workload-weighted advisor.

**"How much faster will this index actually make things?"** →
`apply_index_recommendation` with `mode: "DRY_RUN"` (default). On
Postgres it uses HypoPG to install a virtual index, EXPLAINs each
contributing query, and reports the planner cost delta — no writes
hit the database. If the user wants real timings, pass
`mode: "APPLY_AND_MEASURE"` plus `confirm: true` to actually create
the index (CONCURRENTLY) and run `EXPLAIN ANALYZE` before/after.

**"What changed recently / what should I worry about?"** → Tell the user to
run `deepsql digest` (today) or `deepsql digest 7` (last seven). The digest
isn't MCP-exposed. The digest now includes a workload-weighted **Index Wins**
section that surfaces the same top-N recommendations `get_index_recommendations`
returns, with the `deepsql indexes apply <id> --mode dry-run` CTA — so a user
who skims the digest in Slack can flow directly into the apply path.

**"Are there foreign keys between X and Y?"** → `get_relationships`. Many
real-world DBs lack declared FKs but DeepSQL's brain infers them; check the
`confidence` field and `validationStatus`.

### Feature-development questions (the DBA-consult flow)

**"I'm building a feature that needs a `<thing>` table."** → STOP. Call
`get_brain_context(connectionId, "<thing>")` first. Read the "Treat
DeepSQL like your DBA" section above before generating any DDL. There's
almost always an existing table or column to extend instead of
duplicate.

**"I'm about to write a migration."** → STOP. Run the before-you-commit
checklist (`get_brain_context` + `list_business_rules` +
`get_anti_patterns`). Walk the developer through what you found, THEN
write the migration. The user should see your reasoning, not just the
final SQL.

**"I'm adding a new column to `<table>`."** → STOP. `get_schema` first to
confirm the column doesn't already exist (sometimes under a different
name — `cancelled_at` vs `voided_at` vs `status_history.changed_at`).
`list_business_rules` to check whether nullability, default, or naming
conventions are constrained.

**"I'm naming a foreign key / index."** → `get_relationships` for the FK
convention this team uses, and `get_schema` to see how existing indexes
on the parent table are named. Pick the convention; don't invent a new
one.

**"I'm designing an ORM model / type definition for table `X`."** →
`get_schema(connectionId)` and pull the exact column list, types, and
nullability. Don't infer column types from variable names in the
codebase — they're often stale.

---

## Foot-guns we've watched agents step on

**"I'll write a 4-statement script to set up some temp tables, run my
analysis, and clean up."** → `execute_sql` rejects multi-statement input.
Run each statement separately, or use CTEs (`WITH foo AS (...) SELECT ...`).

**"I'll skip the confirmation step and re-call with `confirmMutation: true`
right away."** → That's an end-run around safety. The confirmation step
exists so a human gets a chance to see the warnings (especially the
"this DELETE has no WHERE clause"-style ones). Always surface the warnings,
always wait for the human.

**"`EXPLAIN ANALYZE` is just better EXPLAIN, I'll always use it."** → No.
ANALYZE executes the query. For SELECTs it's just slower; for mutations it
actually mutates. Default to `useAnalyze: false` and only bump to `true`
when the user actually wants real timings.

**"I don't trust the cached schema; let me query `information_schema`
fresh."** → The cache is invalidated whenever the brain re-indexes the
connection. Trust it. If you really suspect drift, ask the user to run
`deepsql connections init <name> --wait`.

**"I'll just retry on 403."** → A 403 from DeepSQL means the user's token
doesn't have access to that specific connection (RBAC) or role doesn't
allow the action (developer trying to mutate). Surface the error verbatim;
don't pretend it's transient.

**"The SQL looks fine; I'll skip the plan check."** → A cheap
`analyze_query_plan` call has saved more bad joins than any other habit.
Always check the plan before suggesting a query to a user, especially
against unfamiliar schemas.

**"The developer asked for table X, so I'll just create it."** → That's
how databases get fragmented over a year. The brain almost always has a
table doing most of what the new feature needs; you'll find it in 30
seconds with `get_brain_context`. Skipping that step makes you the agent
future engineers curse when they're refactoring around your duplicate
schema. Run the before-you-commit checklist every single time.

**"I read the codebase's models, I know the schema."** → Codebase models
drift from the live schema. Columns get renamed in a migration, the model
class doesn't update. A `User.email` field in TypeScript can be
`users.email_address` in Postgres. `get_schema` is the source of truth;
trust it over `grep`.

---

## What lives in the CLI but not the MCP (yet)

A few categories are CLI-only for now — if the user asks for them, point
them at the terminal command rather than trying to fake it through
`execute_sql`:

| Capability | CLI command |
|---|---|
| Interactive DBA/BI chat agent (the DeepSQL Agent TUI — backed by this same brain, no LLM key needed) | `deepsql agent` (or bare `deepsql` in a terminal) |
| Workload-weighted advisor (terminal mirror of `get_index_recommendations`) | `deepsql indexes top [--limit N]` |
| Apply / dry-run an advisor recommendation (terminal mirror of `apply_index_recommendation`) | `deepsql indexes apply <id> [--mode dry-run\|apply\|apply-and-measure] [--confirm]` |
| Force a fresh accumulation cycle | `deepsql indexes refresh` |
| Full recommendation detail incl. contributing queries | `deepsql indexes show <id>` |
| Dismiss a recommendation | `deepsql indexes dismiss <id>` |
| Browse all recommendations (any status) | `deepsql indexes list [--all\|--status …]` |
| Catalog: missing-index suggestions | `deepsql indexes missing` |
| Catalog: unused / duplicate index detection | `deepsql indexes unused`, `deepsql indexes duplicates` |
| Catalog: per-table index usage stats | `deepsql indexes usage <table>` |
| Catalog: index health report | `deepsql indexes health` |
| Daily digest (anomalies + AI commentary) | `deepsql digest`, `deepsql digest 7` |
| Streaming AI optimization for a slow query | `deepsql slow-queries optimize --query-id <id>` (or MCP `optimize_slow_query` for a synchronous version) |
| Customers driving the most slow-query load | `deepsql slow-queries customers --connection <c>` (or MCP `get_slow_query_customers`) |
| Literal SQL samples for a fingerprint | `deepsql slow-queries samples <fingerprint> --connection <c>` (or MCP `get_query_samples`) |
| AI-flagged hotspots / tail-risk / plan-drift | `deepsql slow-queries insights --connection <c> [--kind hotspots]` (or MCP `get_slow_query_insights`) |
| Trigger an immediate daily analysis run | `deepsql slow-queries trigger --connection <c>` |

These are reachable from any terminal where `deepsql` is installed and
logged in; the saved profile is shared with the MCP server.

---

## A typical session

1. `list_connections` → grab the UUID for the connection the user means.
2. `get_brain_context(connectionId, "the question")` → harvest tables,
   columns, business rules.
3. Write SQL using only the columns the brain surfaced; respect the rules.
4. `analyze_query_plan(connectionId, sql)` → sanity-check the plan
   (`useAnalyze: false`).
5. `execute_sql(connectionId, sql, limit=…)` → fetch results.
6. Summarize, citing which tables you used and which rules you applied.

If you need to mutate (DDL/DML, admin role required):

1–3. Same as above.
4. Show the user the SQL you intend to run, in plain text. **Get their OK.**
5. `execute_sql(connectionId, sql)` → expect `requiresConfirmation: true`
   with warnings.
6. Show the warnings to the user verbatim. Get their **second** OK.
7. `execute_sql(connectionId, sql, confirmMutation: true)` → executes.
8. Tell the user it succeeded; show the `rowCount` of affected rows.

If you're helping a developer build a feature (the DBA-consult flow):

1. Have the developer describe the feature in one or two sentences.
2. `get_brain_context(connectionId, "<feature in one line>")` → surfaces
   existing tables, columns, FKs, and rules in scope.
3. Fill in any gaps: `get_schema(connectionId)` for full column lists,
   `list_business_rules(connectionId, …)` for constraints,
   `get_relationships(connectionId)` for FK conventions,
   `get_anti_patterns(connectionId, kind="table")` for shapes to avoid.
4. **Narrate to the developer what you found before proposing schema.**
   "There's already an X. The team's convention is Y. Anti-pattern Z is
   on watch. Here's what I'd propose, given all that."
5. Once the developer agrees on the *shape*, generate the migration. For
   admins this lands via `execute_sql`; follow the mutation playbook
   above to actually run it. For developers without write access, hand
   the SQL to the user to commit through your codebase's migration
   tooling instead — but make sure the schema you generate already
   reflects everything DeepSQL told you in step 2–3.
6. After the migration runs, `get_schema(connectionId)` to verify the new
   columns exist with the expected types, and optionally re-run
   `analyze_query_plan` on the canonical queries the new feature will
   issue.

If any step returns nothing useful, ask the user to be more specific
rather than guessing. DeepSQL is conservative by design — empty results
from `get_brain_context` usually mean the question was too short or too
schema-y, not that the brain is broken.

---

## Audit, in case you wondered

Every call you make is logged to `security_events` with:

- the user's identity (from the bearer token)
- the editor that invoked the MCP (claude-desktop, cursor-mcp, codex-mcp —
  whatever `DEEPSQL_MCP_USER_ID` is set to in the editor config)
- the connection, statement hash, truncated text, outcome
- whether confirmation was required and given

Workspace admins can search this via the Security tab. Don't do anything
through the MCP that you wouldn't be willing to defend in that view.
