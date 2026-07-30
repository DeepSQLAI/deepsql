# RAG Training: What Happens

This document explains what the app does when you run "RAG Training" and what gets stored or cached.

## Overview

RAG training builds a searchable knowledge base that helps the AI answer SQL questions by
combining:
- Schema context (tables/columns/keys)
- Query examples (natural language + SQL pairs)
- Documentation (business terms and descriptions)

The output is stored in Azure AI Search (preferred) or in-memory embeddings when Azure Search
is disabled.

## RAG vs Chat Memory vs Business Rule Memory

RAG is only one of three learning paths:

- **RAG training (this doc):**
  - Semantic retrieval of schema/query/doc embeddings.
  - Best for broad context and examples.
- **Chat memory (JDBC message window):**
  - Recent turn-by-turn conversation continuity.
  - Not intended for deterministic SQL rule enforcement.
- **Business rule memory (feedback guardrails):**
  - Connection-scoped SQL constraints learned from corrections/teachings.
  - Enforced before SQL execution and during SQL repair loops.

Use feedback/guardrails when users give exact join/filter/table rules that must always be applied.

## Schema Training (the "Train" button)

When you click Train for a connection:
1. **Scan schema**: The app reads tables, columns, and metadata from the target DB.
2. **Build table summaries**: For each table it generates a DDL-like text block.
3. **Create embeddings**: Each table summary is turned into a vector embedding.
4. **Index**:
   - If Azure AI Search is enabled, documents are upserted with deterministic IDs.
   - Otherwise, embeddings are cached in memory for that connection.
5. **Evict RAG cache**: Clears the per-connection retrieval cache so future queries use the
   newest schema.

## Query Example Training

When a query is successful, the app can store the natural language prompt + SQL:
1. Save a `QueryExample` row in Postgres (connection-scoped).
2. Create an embedding.
3. Index to Azure Search (or cache in memory if Azure Search is off).
4. Evict the per-connection RAG cache.

## Documentation Training

When a user adds documentation (table/column/business term):
1. Save `SchemaDocumentation` in Postgres.
2. Create an embedding and index/cache.
3. Evict the per-connection RAG cache.

## Storage & Retrieval

- **Primary store**: Azure AI Search index (vector + keyword search).
- **Fallback**: In-memory embeddings per connection when Azure Search is disabled.
- **Caching**: `ragRetrieval` cache with per-connection keys; eviction happens after training.

## Progress, Status, and History

Training jobs are asynchronous and tracked per connection:
- Statuses: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `REJECTED`.
- Progress updates include current table and percent complete.
- History is persisted in `training_job_history`.
- Retention defaults to keeping the last 50 jobs per connection.

## Cancellation

If you cancel while the job is `PENDING` or `RUNNING`:
- The job is marked `CANCELLED`.
- Any queued work is discarded.
- History is updated immediately.

## API Endpoints (used by the UI)

- `POST /api/training/schema/{connectionId}` — start schema training
- `POST /api/training/schema/cancel/{connectionId}` — cancel schema training
- `GET /api/training/schema/status/{connectionId}` — current status
- `GET /api/training/schema/stream/{connectionId}` — SSE progress updates
- `GET /api/training/history/{connectionId}` — recent history
- `GET /api/training/queue/metrics` — queue depth + concurrency

## Notes

- Schema training is idempotent when Azure Search is enabled (stable document IDs per table).
- Training results are isolated per connection; each connection maintains its own RAG data.
