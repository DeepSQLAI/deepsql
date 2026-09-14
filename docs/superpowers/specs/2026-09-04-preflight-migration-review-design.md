# Pre-flight Migration Review (DDL Safety)

**Status:** approved design, pending implementation
**Date:** 2026-09-04
**Branch:** `feat/preflight-migration-review`

## Problem

`SchemaChangeTrackingService` is retrospective: it snapshots the schema and reports
what already changed. Nothing answers the question a DBA actually asks *before*
running a migration — "will this lock the table, and for how long?"

Agent chat can approximate an answer today (`get_schema`, `get_table_growth`,
`execute_sql`), but it answers from model recall. DDL lock semantics are precise,
version-dependent and counterintuitive, and a wrong answer arrives with the same
confidence as a right one. For a question whose entire purpose is deciding whether
to run something against production, that is worse than no tool.

Evidence this is a real hazard, not a theoretical one: while writing this spec the
author twice asserted that a volatile `DEFAULT now()` forces a table rewrite. It
does not — `now()` is STABLE. Measured against a real Postgres, not recalled.

## Scope

A deterministic analyzer exposed as **one MCP tool**, `analyze_migration`. No new
UI section, no new frontend surface. The Agent remains the interface; the tool
makes its verdict verifiable.

Explicitly out of scope: executing migrations, rewriting them automatically, a
history of past analyses, MySQL support.

## Convention this follows

The codebase already draws a consistent line, and this sits on the deterministic
side of it (22 of 165 services touch an LLM; 40 of 42 brain services do not):

- **The database or arithmetic over its catalog decides the verdict.**
  `IndexAdvisorService` scores index health with explicit arithmetic and estimates
  impact via `hypopg` + `pg_class`. `SchemaEvolutionRiskService` tiers risk from
  size thresholds with no model at all.
- **The LLM narrates.** `ExplainPlanService` lets Postgres produce the plan — ground
  truth — and uses a model only to interpret it.

`analyze_migration` returns structured facts. The Agent writes the prose.

## Design

### Pipeline

1. **Parse** with JSqlParser (already a dependency) into a fact set:
   operation, table, column, target type, default expression, nullability,
   `CONCURRENTLY` presence.
2. **Fail closed.** Unparseable or unrecognised input returns `UNKNOWN` with
   `safeToRun: false`. Never "looks fine." (CLAUDE.md: leading-keyword
   classification is how a non-admin wiped tables through the Editor.)
3. **Classify** against a per-dialect rule table keyed on
   `(operation, discriminating conditions)`, yielding lock mode + rewrite flag.
4. **Scale** by live catalog data — `pg_total_relation_size()`, row estimate — to
   convert "rewrites the table" into a duration bucket.

### The discriminator that matters

Identical syntax, different consequences, decided by `pg_proc.provolatile` of the
default expression's function — queried live, so any function classifies correctly
rather than only hardcoded ones:

| Statement | provolatile | Rewrites |
|---|---|---|
| `ADD COLUMN c text` | — | no |
| `ADD COLUMN c text DEFAULT 'x'` | — (constant) | no |
| `ADD COLUMN c timestamptz DEFAULT now()` | `s` stable | **no** |
| `ADD COLUMN c uuid DEFAULT gen_random_uuid()` | `v` volatile | **yes** |
| `ADD COLUMN c double precision DEFAULT random()` | `v` volatile | **yes** |
| `ADD COLUMN c timestamptz DEFAULT clock_timestamp()` | `v` volatile | **yes** |

### Measured rule table (Postgres 18.4)

Every row verified against a live instance via `pg_locks` and
`pg_relation_filenode()`, not from documentation or recall:

| Operation | Lock | Rewrites | Verdict |
|---|---|---|---|
| `ADD COLUMN` (no default / constant / stable default) | AccessExclusive | no | SAFE |
| `ADD COLUMN` (volatile default) | AccessExclusive | **yes** | DANGER if large |
| `ADD COLUMN NOT NULL` (no default, non-empty table) | — | — | FAILS: `contains null values` |
| `ALTER COLUMN TYPE` narrowing (`text`→`varchar(50)`) | AccessExclusive + Share | **yes** | DANGER if large |
| `ALTER COLUMN TYPE` widening (`varchar(50)`→`varchar(100)`) | AccessExclusive | no | SAFE |
| `SET NOT NULL` on existing column | AccessExclusive | no | CAUTION (full scan to validate) |
| `DROP COLUMN` | AccessExclusive | no | SAFE (metadata only) |
| `RENAME COLUMN` | AccessExclusive | no | SAFE (breaks app code, not the DB) |
| `CREATE INDEX` | AccessExclusive + Share | n/a | DANGER: blocks writes for the build |
| `CREATE INDEX CONCURRENTLY` | ShareUpdateExclusive | n/a | SAFE, can leave an invalid index |
| `ADD CHECK` (validating) | AccessExclusive | no | CAUTION (full scan to validate) |
| `ADD CHECK ... NOT VALID` | AccessExclusive | no | SAFE (no scan; validate separately) |
| `ADD FOREIGN KEY` (validating) | ShareRowExclusive **on both tables** | no | DANGER: blocks writes on the referenced table too |
| `ADD FOREIGN KEY ... NOT VALID` | ShareRowExclusive on both tables | no | CAUTION (still locks both, but skips the scan) |
| `VALIDATE CONSTRAINT` | ShareUpdateExclusive | no | SAFE (concurrent writes allowed) |

**Lock type and duration are independent axes.** `ADD COLUMN` with no default takes
AccessExclusive — the strongest lock — yet is harmless because it is held for
milliseconds. The same lock across a 48M-row rewrite is the outage. The tool must
report both; reporting the lock name alone is alarming and useless.

**A statement can lock tables it does not name.** `ADD FOREIGN KEY` takes
`ShareRowExclusiveLock` on the *referenced* table as well as the altered one —
measured, and the reason `child`-side migrations cause unexplained write stalls on
a parent table nobody touched. The output therefore reports locks **per table**,
not as a single field, and names every table affected.

`NOT VALID` is the alternative worth surfacing for both constraint types: it takes
the same lock but skips the validating scan, so the expensive half moves to a
separate `VALIDATE CONSTRAINT` that runs under `ShareUpdateExclusiveLock` and
allows concurrent writes. Two cheap steps instead of one long block.

### Output shape

```json
{
  "dialect": "postgres",
  "verdict": "DANGER",
  "safeToRun": false,
  "operation": "ADD_COLUMN",
  "table": "orders",
  "locks": [
    { "table": "orders", "mode": "AccessExclusiveLock", "blocks": ["read", "write"] }
  ],
  "rewritesTable": true,
  "tableRows": 48000000,
  "tableSizeBytes": 9200000000,
  "estimatedDuration": "minutes-to-tens-of-minutes",
  "reason": "Default expression gen_random_uuid() is VOLATILE, forcing a full table rewrite.",
  "saferAlternative": "Add the column without a default, backfill in batches, then set the default.",
  "docsUrl": "https://www.postgresql.org/docs/18/sql-altertable.html",
  "confidence": "verified"
}
```

`estimatedDuration` is a coarse bucket, never a false-precision number — real time
depends on disk, cache and load, none of which table size predicts. A numeric
range would imply an accuracy the inputs cannot support; the bucket is the
honest form of the answer.

### Placement

- `MigrationRiskProvider` in `provider/api/`, implemented by
  `PostgresMigrationRiskProvider`, reached via `DatabaseDialect` — following the
  registry rule (no if/else on database type).
- `MigrationRiskService` orchestrates parse → classify → scale.
- MySQL: `UnsupportedDatabaseException`. Explicitly unsupported beats a confident
  wrong answer, and matches `IndexAdvisorService`'s Postgres-only catalog queries.

### Authorization

Read-only: catalog queries plus parsing, no DDL executed. Takes a caller-supplied
`connectionId`, so it **must** call `accessControlService.assertCanReadConnectionContent`
— per CLAUDE.md, no filter does this for you.

## Verification

Three layers, and layer 2 is the one that matters.

1. **Unit tests** over the rule table. Proves consistency, *not* correctness — if a
   rule is encoded wrong, the test enshrines the error.
2. **Execution against a real Postgres.** For each rule: run the DDL in a
   transaction, read the lock from `pg_locks`, compare `pg_relation_filenode()`
   before and after, roll back. The engine testifies about itself. This is what
   caught the `now()` error. Runs in CI so an upstream behaviour change fails the
   build instead of silently making the tool wrong.
3. **Docs citation** per rule, so any verdict can be audited in ~30 seconds.

Implementation note: rank locks by an explicit strength ordering. `max(mode)` is
alphabetical — "ShareLock" sorts above "AccessExclusiveLock" — which misreported
the strongest lock during the spike.

## Known limits

- Coverage is only the encoded shapes; everything else returns `UNKNOWN`.
- Duration is a bucket, not a prediction.
- Postgres only.
- Verified against 18.4; older majors differ (pre-11 rewrites on *any* default).
  The rule table is version-tagged and the CI probe pins the same major.

## MCP release checklist

Per CLAUDE.md, a new MCP tool requires all of these in the same commit:
tool definition + handler + result builder + summarizer, CLI dispatcher, CLI help
text, `SKILL_BODY.md` tool table and count, `mcp/CLAUDE.md`, `mcp/README.md`,
`mcp/package.json` minor bump.
