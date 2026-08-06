---
name: workload-analysis
description: Explain what's driving database load — slow-query hotspots, regressions, per-customer load, and table growth — across the whole workload.
version: 1.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [workload, slow-queries, regression, hotspots, growth, anomaly, customers, deepsql]
    related_skills: [slow-query-optimize, index-advisor]
---

# Workload Analysis

Use for whole-workload questions: "what's slow right now?", "what regressed this week?", "which customer is driving load?", "what's growing fast?" For a single named query, use `slow-query-optimize`.

## Procedure

1. **Resolve the connection** (`list_connections` → UUID).

2. **Find the hotspots.** `get_slow_query_insights(connectionId, kind="all", window=…)` returns pre-computed AI insights grouped as `hotspots` (most total DB time), `remediation` (actionable fixes), `tail-risk` (p95/max outliers), `plan-drift` (plan changed), `skew` (one tenant overloaded). `analyze_slow_queries` and `list_tracked_queries` give the raw fingerprint list with call counts and mean/max times.

3. **Catch regressions.** `get_query_regressions(connectionId)` ranks queries that got slower on the latest analysis run by slowdown factor. Drill into one with `get_slow_query_timeline(queryId)` to see the day-by-day trend.

4. **Attribute load.** `get_slow_query_customers(connectionId)` ranks tenants/customers by total slow-query time (with resolved customer name when configured) — answers "who is driving the load?"

5. **Watch growth.** `get_table_growth(connectionId)` for size/row-count trends; `get_growth_anomalies(connectionId)` for tables growing abnormally. These predict the next performance cliff before it hits.

6. **Synthesize, then route.** Lead with the few things that matter most (biggest total-time consumer, worst regression, fastest-growing table). For each, hand off to the right next step: a specific slow query → `slow-query-optimize`; an indexing opportunity → `index-advisor`.

## Guardrails

- Rank by **total** impact (`calls × mean_exec_time`), not by single-execution worst case — a 5-second query run twice a day matters less than a 200ms query run a million times.
- Start with compact persisted analytics (`get_latest_slow_query_analysis`, `get_slow_query_insights`, `list_tracked_queries`). If a persisted payload is too large/truncated to inspect cleanly, pivot to the compact endpoints rather than trying to parse the oversized blob.
- Use `analyze_slow_queries` when you need a fresh top-N snapshot for the last 24 hours; say explicitly that this triggers fresh analysis work, unlike the persisted analytics endpoints.
- Everything here is read-only and analytics-store backed unless you intentionally call `analyze_slow_queries` for a fresh collection. Say so if the user worries about adding load.
- `get_query_samples` exposes literal bind values — treat the output as potentially sensitive data.
