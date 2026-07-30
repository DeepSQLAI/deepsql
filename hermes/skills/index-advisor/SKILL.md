---
name: index-advisor
description: Recommend which indexes to add or drop for a connection, using DeepSQL's workload-weighted advisor, and optionally dry-run/apply them.
version: 1.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [index, indexes, advisor, performance, create-index, drop-index, unused, deepsql]
    related_skills: [slow-query-optimize, workload-analysis]
---

# Index Advisor

Use when the user asks what indexes to add or drop, or "how do I speed up this workload with indexes?" Indexes are a **whole-workload** decision — never recommend one off a single query in isolation; that's what this advisor is for.

## Procedure

1. **Resolve the connection** (`list_connections` → UUID).

2. **Get the workload-weighted recommendations.** `get_index_recommendations(connectionId)` returns the pre-computed top-N (default 5) ranked by net benefit (`Σ calls × mean_exec_time` − write cost). Each carries the contributing query fingerprints, the role each column played, and (on Postgres) a HypoPG cost-delta. This covers both `CREATE_INDEX` and `DROP_INDEX` (unused / redundant-prefix) candidates.

3. **Add catalog context** when relevant: `get_index_health(connectionId)` for the overall picture, `get_unused_indexes` / `get_duplicate_indexes` for dead weight, `get_missing_indexes` for catalog-level suggestions, `get_table_index_usage(table)` for one hot table.

4. **Explain each recommendation** in terms the user can act on: which queries it helps, expected benefit, and write-cost trade-off. Don't just dump the list.

5. **Estimate impact before applying.** `apply_index_recommendation(recommendationId, mode="DRY_RUN")` (default) uses HypoPG on Postgres to install a virtual index and EXPLAIN the contributing queries — zero writes. Report the planner cost delta.

6. **Apply only on explicit request, admin only.** `mode="APPLY"` (real `CREATE/DROP INDEX CONCURRENTLY`) or `APPLY_AND_MEASURE` (also runs `EXPLAIN ANALYZE` before/after) require `confirm: true`. The DDL is server-generated from the recommendation row — you never supply index SQL. Surface what will run and get a human OK first.

## Guardrails

- Recommend against an existing covering index/constraint instead of a redundant single-column one — `get_index_health` / `get_table_index_usage` will show it.
- A `DROP_INDEX` recommendation for an "unused" index still deserves a sanity check: confirm with the user it isn't a rarely-used-but-critical path before applying.
- `apply_index_recommendation` is the only write-capable MCP tool. Treat every `APPLY` like a production change.
