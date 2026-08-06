---
name: slow-query-optimize
description: Diagnose why a query is slow and propose a rewrite, using DeepSQL's plan analysis and AI query optimizer.
version: 1.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [slow, query, optimize, explain, plan, performance, rewrite, regression, deepsql]
    related_skills: [index-advisor, workload-analysis]
---

# Slow Query Optimize

Use when the user points at a specific query and asks "why is this slow?" or "make this faster." For workload-level "what's slow overall?" use `workload-analysis` instead.

## Procedure

1. **Resolve the connection** (`list_connections` → UUID).

2. **Analyze the plan.** `analyze_query_plan(connectionId, sql)` returns the parsed plan tree, performance issues, missing-index hints, and a written summary that already accounts for the connection's schema and business rules. Use `useAnalyze: false` by default — `useAnalyze: true` actually executes the query (and for a mutation, triggers the same admin + confirm gate as `execute_sql`).

3. **Get an AI rewrite** for a specific statement with `optimize_slow_query(connectionId, sql, avgExecutionTimeMs=…)`. Pass the average execution time to anchor the impact estimate. Note: this is single-query scoped and does NOT recommend indexes — route index questions to the `index-advisor` skill.

4. **If the query came from the live workload**, identify it by fingerprint first: `analyze_slow_queries` (recent slow queries with fingerprints) → `get_query_samples(fingerprint)` for a real statement with bind values to plan against → `get_slow_query_timeline(queryId)` to confirm whether it's actually getting slower.

5. **Report:** the root cause (slow node, bad estimate, missing index, plan drift), the proposed rewrite, and the expected improvement. If the real fix is an index, say so and hand off to `index-advisor`.

## Guardrails

- Don't run a mutation just to time it. `useAnalyze: true` on an `UPDATE`/`DELETE` executes it — only do that with admin role and explicit confirmation.
- A rewrite that changes result semantics is a bug, not an optimization. Preserve the business rules (filters, soft-deletes) the original query respected.
