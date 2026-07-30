---
name: schema-exploration
description: Map or describe a database — what it tracks, its tables, relationships, and conventions — grounded in DeepSQL's cached schema and brain.
version: 1.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [schema, explore, tables, relationships, foreign-keys, describe, deepsql, database]
    related_skills: [bi-query, workload-analysis]
---

# Schema Exploration

Use when the user wants to understand the database itself ("what does this DB track?", "what tables exist?", "how are orders and customers related?", "describe the bookings table").

## Procedure

1. **Resolve the connection** (`list_connections` → UUID) if you don't have it.

2. **Get the shape.** `get_schema(connectionId)` for tables, columns, types, declared FKs. `get_database_objects(connectionId)` when you also need views/functions/procedures, not just columns. The schema is cached and authoritative — trust it over the codebase.

3. **Get the meaning.** `get_brain_context(connectionId, "<what the user is asking about>")` for the domain layer: what the tables mean, business terms, documentation. This is what turns "a list of tables" into "what the database tracks."

4. **Fill relationship gaps.** Many real databases lack declared foreign keys. `get_relationships(connectionId)` returns inferred + validated FKs with a `confidence` score and `validationStatus`. Report the confidence — a 0.95 inferred FK is reliable; a 0.4 one is a guess.

5. **Surface the rules and traps.** `list_business_rules(connectionId)` and `get_anti_patterns(connectionId, kind="table")` so the user learns the conventions and known schema smells, not just the structure.

## Reporting

- Lead with **what the database is for**, then the largest/most central tables, then notable relationships.
- For "largest tables," use the row counts from `get_schema` (don't run `COUNT(*)` across every table).
- Flag anti-patterns and low-confidence inferred FKs explicitly — they're the things a user most needs to know and least expects.
