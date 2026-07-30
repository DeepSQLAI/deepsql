# Chat Memory, RAG, and SQL Guardrails

This document explains the three persistence paths used by chat, how they differ, and how they work together.

## Why "memory" can appear inconsistent

Not all knowledge belongs in the same store. The agent now uses three separate memory layers:

1. **Conversation memory (JDBC chat memory)**
   - Stores recent message history for a chat thread.
   - Scope: `chatId` (UUID), effectively tied to a specific connection chat.
   - Purpose: preserve dialog continuity and follow-up context.

2. **RAG training memory (embeddings)**
   - Stores schema DDL, query examples, and documentation as embeddings.
   - Scope: connection-specific training corpus.
   - Purpose: semantic retrieval for SQL generation quality.

3. **Business rule memory (feedback-driven SQL guardrails)**
   - Stores learned SQL constraints from user corrections/teachings.
   - Scope: strictly connection-scoped and schema-compatible.
   - Purpose: deterministic protection against bad joins/filters.

## What goes where

### Put this in RAG embeddings
- Schema descriptions and terminology.
- Query examples (NL -> SQL).
- General documentation that improves retrieval.

### Put this in business-rule guardrails
- Deterministic instructions such as:
  - use table A instead of table B
  - must join on `group_id`
  - must include `type='CREDIT'` and `mode='SUBSCRIPTION'`

### Keep this in chat memory only
- Thread-local conversational context.
- Temporary clarification turns and follow-up references.

## Current backend flow

### 1) Learning from feedback
- `FeedbackService` stores feedback events.
- For learnable types (`CORRECTION`, `TEACHING`, `COLUMN_VALUES`), it calls `BusinessRuleMemoryService.learnFromFeedback(...)`.
- Rules are persisted as `BrainRule` rows with SQL rule types:
  - `SQL_REQUIRED_TABLE`
  - `SQL_PROHIBITED_TABLE`
  - `SQL_REQUIRED_PREDICATE`
  - `SQL_REQUIRED_JOIN_KEY`

### 2) Resolving relevant rules at query time
- `ChatService` calls `BusinessRuleMemoryService.resolveApplicableGuardrails(connectionId, question, schema)`.
- Rule selection is constrained by:
  - connection id
  - schema compatibility (when schema is available)
  - question token overlap

### 3) Prompt injection
- `ChatService` appends `buildGuardrailContext(...)` output into feedback context.
- This gives the model explicit, connection-scoped SQL constraints.

### 4) Deterministic SQL validation and retry
- Before execution, `ChatService` validates SQL via `evaluateSql(...)`.
- If guardrails are violated, execution is blocked and SQL repair/refinement loops run.
- Every corrected candidate is re-validated against the same guardrails.

## Example rule behavior

If a user teaches:
- "For subscription revenue, use `ACCOUNTS` and `ACCOUNTS_LEDGER`, join on `group_id`, and filter `type=CREDIT` and `mode=SUBSCRIPTION`"

Then for matching questions on that connection, the agent:
- adds those constraints to prompt context
- rejects SQL that uses disallowed joins/tables
- keeps iterating until SQL passes guardrail checks or attempts are exhausted

## Connection scoping and schema safety

Business rules are intentionally generic and schema-aware:
- No hardcoded table assumptions across all connections.
- Main chat-path code must also stay schema-agnostic: no customer-specific table names, column names, SQL fragments, or prompt-to-table shortcuts in classifier, planner, resolver, composer, or execution logic.
- Rules are only applied when they fit the active connection and (if available) current schema.
- This prevents leaking business logic between unrelated databases.

## APIs

- `GET /api/memory/status`
  - Shows JDBC chat memory availability and effective window.
- `POST /api/feedback/*`
  - Captures user feedback used for long-term learning.
- `GET /api/business-rules/connection/{connectionId}`
  - Returns active rules, applicable guardrails, and rendered guardrail context.
- `POST /api/business-rules/connection/{connectionId}/learn`
  - Manually ingest rule text.

## Key configuration

- `app.chat.memory.max-messages`
  - Message window size for JDBC chat memory.
- `app.chat.sql-fix.max-attempts`
  - Max SQL correction/refinement retries.
- `app.chat.auto-learn-feedback.enabled`
  - Enables auto-learning from explicit user corrections.
- `app.chat.auto-learn.max-feedback-context-chars`
  - Caps feedback+guardrail context length injected into prompts.
- `db.query-timeout-seconds`
  - JDBC statement timeout used during query execution.

## Validation tests

Core tests covering this flow:
- `BusinessRuleMemoryServiceTest`
- `FeedbackServiceTest`
- `BusinessRuleControllerIntegrationTest`

Connection integration coverage (including configured test connection):
- `ConnectionControllerIntegrationTest`
- `SlowQueryControllerIntegrationTest`
