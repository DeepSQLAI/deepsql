---
name: bi-query
description: Answer a question about the data — write and run grounded, read-only SQL against a DeepSQL connection and report the result.
version: 1.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [sql, bi, analytics, query, count, report, deepsql, database]
    related_skills: [schema-exploration, slow-query-optimize]
---

# BI Query

Use when the user asks a question whose answer is **in the data** ("how many bookings last week?", "revenue by region", "top 10 customers"). The output is a number/table, not schema advice.

## Procedure

1. **Resolve the connection.** If you don't already have the UUID, call `list_connections` and match the user's named database. Pass the UUID to every later call.

2. **Ground.** Call `get_brain_context(connectionId, "<the user's question>")`. Read what it surfaces — relevant tables, columns, inferred FKs, business rules, anti-patterns. Do not skip this even if you think you know the table.

3. **Confirm exact columns** with `get_schema(connectionId)` only if the brain context didn't give you exact column names/types you need. Don't query `information_schema`.

4. **Write ONE statement.** Table-qualify every column. Apply every relevant business rule (e.g. cancelled/soft-delete filters) without being asked. Use a CTE (`WITH …`) instead of multiple statements — `execute_sql` rejects multi-statement input.

5. **Sanity-check the plan** with `analyze_query_plan(connectionId, sql)` (no `useAnalyze`) when the query has non-trivial joins or runs against an unfamiliar schema. It's cheap and catches bad joins before you show the user a wrong number.

6. **Run it** with `execute_sql(connectionId, sql, limit=…)`. Remember: default 100 rows, max 1000. For a total, `SELECT COUNT(*)` rather than counting a truncated result set.

7. **Answer only.** Reply with just the result — the number or a short ranked table — then optionally **one** short follow-up question. Apply business rules silently; do NOT append "Grounding used" / "Filters applied" / "Used:" / tool-narration / column-mapping sections. Only if the user asks how you got it do you show the tables, joins, and filters.

## Guardrails

- Read-only only. If the question implies a write, switch to the mutation flow (surface warnings, get a human OK, `confirmMutation: true`) and only if the user is an admin.
- If `get_brain_context` returns nothing useful, do **not** guess a table from memory. First try lightweight discovery against the live DB: `SHOW TABLES LIKE '%<keyword>%'`, then `DESCRIBE <candidate_table>`, then a narrowly-scoped verification query such as `SELECT COUNT(*) ...`. Ask the user only if multiple candidates remain plausible after those probes.
- If schema/object metadata is too large or `get_database_objects` times out, prefer targeted read-only probes over broad catalog dumps.
- When the user asks for a chart or ranking and brain context is thin, first identify the fact table and dimension join path with small probes, then run the final aggregation. Example pattern used successfully in `analytics_db`: `CUSTOMER_ORDERS.customer_id -> CUSTOMERS.id` for city-level booking rollups, and `PRICE_BREAKDOWN.booking_id -> CUSTOMER_ORDERS.id -> CUSTOMERS.id` for customer-level room-price averages.
- If the user says "active customers" or similar status language, check brain context for status-source rules before using a status column. In `analytics_db`, `PRODUCT_PRICING.property_status` is the correct source and `CUSTOMERS.onboarding_status` is specifically the wrong one.
- For chart requests in chat-only environments, still run the real aggregation query first. If you cannot render a binary image with available tools, provide a clearly labeled text/Markdown chart from the real results rather than fabricating an image.
- Never present a truncated (limit-capped) result as a total.
