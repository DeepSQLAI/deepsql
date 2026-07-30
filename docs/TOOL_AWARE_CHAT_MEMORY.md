# Tool-Aware Chat Memory (Current Implementation)

This project currently uses Spring AI JDBC chat memory plus explicit history synchronization in `ChatService`.

## Current behavior

### Primary persistence
- Canonical history is stored in application chat tables via `ChatHistoryService`.
- Spring AI `ChatMemory` is JDBC-backed and used as the active conversation window during model calls.

### Why synchronization exists

`ChatService` can run multi-pass model calls in one request:
- initial SQL generation
- SQL repair attempts
- sparse-result refinements
- final summarization

Without guardrails, these internal turns can pollute memory and reduce future quality. To avoid that, the service:
- writes canonical user/assistant turns to chat history
- calls `syncConversationMemoryFromHistory(chatId)` to re-align JDBC chat memory to final turns only

## How this differs from RAG and feedback learning

- Chat memory: recent conversation continuity.
- RAG: semantic retrieval of schema/examples/docs.
- Feedback/guardrails: deterministic, connection-scoped business SQL constraints.

See `CHAT_MEMORY_AND_GUARDRAILS.md` for full architecture.

## Relevant classes

- `SpringAIChatMemoryConfig`
  - Creates `MessageWindowChatMemory` with configured window size.
- `ChatMemoryService`
  - Availability checks, window verification, conversation utilities.
- `ChatMemoryController`
  - Status/history endpoints for operational checks.
- `ChatService`
  - Main chat pipeline and memory synchronization logic.

## Operational checks

1. Verify memory availability:
   - `GET /api/memory/status`
2. Verify effective window size equals configured/expected.
3. Check a specific chat history:
   - `GET /api/memory/connection/{connectionId}/chat/{chatId}`

## Common confusion

If users say "it is not remembering":
- check whether they expect thread memory, semantic retrieval, or hard business rule enforcement.
- use chat memory for thread continuity.
- use training endpoints for RAG corpus.
- use feedback/business-rule endpoints for deterministic SQL constraints.
