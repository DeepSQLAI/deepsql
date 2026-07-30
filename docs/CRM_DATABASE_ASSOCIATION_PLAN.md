# Plan: Associate CRM Connections with Database Connections + Chat Ticket Integration

## Context

HubSpot tickets are synced and stored in `support_tickets` but the chat system has zero awareness of them. When a user asks "How many urgent hubspot tickets do we have?" while chatting with a database connection (e.g., `aws-rds-master`), the system can't answer because:
1. There's no link between CRM connections and database connections
2. The chat pipeline never queries the `support_tickets` table
3. No ticket context type exists in `ChatContextAssembler`

Additionally, RAG may return HOTEL table metadata (from test fixtures) due to semantic similarity, since no proper ticket context exists.

**Goal:** Add a `database_connection_id` FK to `crm_connections`, then integrate ticket data into the chat system so ticket-related questions are answered correctly.

---

## Phase 1: Database Migration

**Create** `backend/src/main/resources/db/migration/V62__add_database_connection_id_to_crm_connections.sql`
- Add nullable `database_connection_id VARCHAR(36)` to `crm_connections`
- FK to `encrypted_credentials(id)` with `ON DELETE SET NULL`
- Index on `database_connection_id` for chat lookups

## Phase 2: Backend Entity + Repository

**Modify** `backend/src/main/java/com/dbaagent/ticket/model/CrmConnection.java`
- Add `@Column(name = "database_connection_id", length = 36) private String databaseConnectionId`

**Modify** `backend/src/main/java/com/dbaagent/ticket/repository/CrmConnectionRepository.java`
- Add `findAllByDatabaseConnectionIdAndActiveTrue(String databaseConnectionId)`

**Modify** `backend/src/main/java/com/dbaagent/ticket/repository/SupportTicketRepository.java`
- Add count/query methods:
  - `long countByConnectionIdIn(List<String> connectionIds)`
  - `long countByConnectionIdInAndSource(List<String> connectionIds, String source)` — for source-filtered counts
  - `long countByConnectionIdInAndPriorityIgnoreCase(List<String> connectionIds, String priority)`
  - `List<SupportTicket> findTop20ByConnectionIdInOrderByModifiedAtExtDesc(List<String> connectionIds)`
  - `@Query` for distinct priority values: `SELECT DISTINCT s.priority FROM SupportTicket s WHERE s.connectionId IN :connectionIds AND s.priority IS NOT NULL AND s.priority <> ''`
  - `@Query` for distinct status values: `SELECT DISTINCT s.status FROM SupportTicket s WHERE s.connectionId IN :connectionIds AND s.status IS NOT NULL AND s.status <> ''`
  - `@Query(nativeQuery = true)` for priority breakdown counts: `SELECT priority, COUNT(*) FROM support_tickets WHERE connection_id IN (:connectionIds) AND priority IS NOT NULL AND priority <> '' GROUP BY priority ORDER BY COUNT(*) DESC LIMIT 20` — returns `List<Object[]>`, service converts to `Map<String, Long>`. Must use native query because JPQL does not support `LIMIT`. Filters nulls/blanks to prevent "null" buckets in user-facing summaries.
  - `@Query(nativeQuery = true)` for status breakdown counts: `SELECT status, COUNT(*) FROM support_tickets WHERE connection_id IN (:connectionIds) AND status IS NOT NULL AND status <> '' GROUP BY status ORDER BY COUNT(*) DESC LIMIT 20` — same pattern.
  - `@Query(nativeQuery = true)` for source-filtered priority breakdown: same as above with `AND source = :source` clause
  - `@Query(nativeQuery = true)` for source-filtered status breakdown: same pattern
  - `List<SupportTicket> findTop20ByConnectionIdInAndPriorityIgnoreCaseOrderByModifiedAtExtDesc(List<String> connectionIds, String priority)` — for priority-filtered recent tickets (Spring Data derived query, no `@Query` needed)

**Rationale**: HubSpot stores raw `hs_ticket_priority` values (could be "HIGH", "MEDIUM", "LOW", numeric, or custom strings) and `status` is actually the pipeline stage name (e.g., "New", "Waiting on contact", "1"). We must NOT hardcode expected values — instead use GROUP BY queries to discover what's actually in the data.

## Phase 3: TicketContextService (New)

**Create** `backend/src/main/java/com/dbaagent/ticket/service/TicketContextService.java`
- Bridge between database connectionId and ticket data
- Mark with `@Service` annotation (Spring component scan covers `com.dbaagent.ticket.service` package)
- Methods:
  - `getCrmConnectionIdsForDatabase(databaseConnectionId)` → `List<String>` — **guards against empty list** (returns early if no CRM connections found, avoids empty IN clause)
  - `countTicketsForDatabase(databaseConnectionId, source)` → `long` — `source` is `@Nullable`; if provided, filters by CRM source (e.g., "HUBSPOT"). If null, counts across all sources.
  - `getTicketPriorityBreakdown(databaseConnectionId, source)` → `Map<String, Long>` — calls source-filtered or unfiltered native GROUP BY query
  - `getTicketStatusBreakdown(databaseConnectionId, source)` → `Map<String, Long>` — same pattern
  - `getRecentTicketsForDatabase(databaseConnectionId, source)` → `List<SupportTicket>` (top 20) — `source` is `@Nullable`; if provided, filters by CRM source
  - `getRecentTicketsByPriority(databaseConnectionId, priority, source)` → `List<SupportTicket>` — same nullable source filtering
  - `hasCrmConnections(databaseConnectionId)` → `boolean`
  - `isTicketQuestion(String message)` → `boolean` (centralized detection for reuse by ChatService and ChatContextAssembler)
  - `isTicketOnlyQuestion(String message)` → `boolean` — stricter check: returns true ONLY when the question is purely about ticket data (counts, lists, summaries) with no database/SQL dimension. Returns false for mixed questions like "show me users who filed urgent tickets" or "join ticket data with our accounts table." Used for anti-hallucination bypass decisions.
  - `extractSource(String message)` → `Optional<String>` — extracts CRM source from message ("hubspot" → "HUBSPOT", "zendesk" → "ZENDESK", etc.). Returns empty if no source mentioned. Used to filter tickets by source when user specifies one.
- All read-only `@Transactional(readOnly = true)`
- **Empty list guard**: ALL methods that pass connectionIds to repository queries must check `if (crmIds.isEmpty()) return <default>;` BEFORE calling repository methods to prevent JPA empty IN clause errors

## Phase 4: CrmConnectionService + Controller

**Modify** `backend/src/main/java/com/dbaagent/ticket/service/CrmConnectionService.java`
- Add `databaseConnectionId` parameter to `save()` method (line 41)
- Add `updateDatabaseConnectionId(id, databaseConnectionId)` method
- Add `findActiveByDatabaseConnectionId(databaseConnectionId)` method

**Modify** `backend/src/main/java/com/dbaagent/ticket/controller/CrmConnectionController.java`
- `create()` (line 32): extract `databaseConnectionId` from payload, pass to service as 6th arg
- `update()` (line 107): accept `databaseConnectionId` updates
- `toResponse()` (line 162): include `databaseConnectionId` in response map
- **SECURITY (defense-in-depth)**: `create()` and `update()` are already `@PreAuthorize("hasRole('ADMIN')")`, so only admins can link. As defense-in-depth, validate that `databaseConnectionId` (if provided) actually exists in `encrypted_credentials` — return 400 if not found. This prevents linking to nonexistent connections (e.g., stale UUIDs).

## Phase 5: Chat Integration (Critical — Robustness Fixes)

### 5a. ChatContextAssembler — Context Type + Builder

**Modify** `backend/src/main/java/com/dbaagent/service/ChatContextAssembler.java`

1. Add `SUPPORT_TICKETS` to `ContextType` enum (line 65)

2. Inject `TicketContextService` (add final field, constructor handles via `@RequiredArgsConstructor`)

3. Add ticket detection in `determineNeededContext()` (before line 189 return):
```java
if (lowerMessage.matches(".*(ticket|tickets|support ticket|crm|hubspot|zendesk|jira).*") ||
    lowerMessage.matches(".*(urgent|priority|escalat).*(ticket|issue|support).*") ||
    lowerMessage.matches(".*(ticket|issue|support).*(urgent|priority|escalat).*")) {
    needed.add(ContextType.SUPPORT_TICKETS);
}
```

4. Add `buildTicketContext(String databaseConnectionId, String source)` method:
   - `source` is `@Nullable` — extracted from user question by caller, passed through to all TicketContextService calls
   - Uses `TicketContextService` to get data-driven breakdowns (not hardcoded)
   - Queries actual DISTINCT priority/status values via GROUP BY, filtered by source when provided
   - Includes up to 20 recent ticket subjects (truncated to 80 chars each), source-filtered
   - Header indicates source when filtered: "=== SUPPORT TICKET INTELLIGENCE (HUBSPOT) ===" vs "=== SUPPORT TICKET INTELLIGENCE ==="
   - Total output ~500-800 tokens max
   - Returns `""` if no CRM connections or no tickets

5. **Token budget**: Add `ticketContext` as proper parameter to `applyTokenBudget()`:
   - Change signature: add `String tickets` parameter (between `training` and `dbRules`)
   - Add to truncation priority array — truncate FIRST (before training), since it's supplementary CRM data
   - Update return array to 7 elements: `{schema, classification, performance, brain, feedback, training, tickets}`
   - Update all callers in ChatService (line ~1528) to handle 7-element array

### 5b. ChatService — Fast Path + Anti-Hallucination Guard + Streaming

**Modify** `backend/src/main/java/com/dbaagent/service/ChatService.java`

1. Inject `TicketContextService` via **manual constructor update** (ChatService uses `@Autowired` explicit constructor at line 122, NOT `@RequiredArgsConstructor`):
   - Add field: `private final TicketContextService ticketContextService;`
   - Add constructor parameter and assignment (after `sqlExecutionPipeline` param)

2. Add `tryTicketFastPath(message, connectionId)` method:
   - Detects ticket count/list/summary questions
   - Extracts source via `ticketContextService.extractSource(message)` — "hubspot tickets" → filters to HUBSPOT only; "how many tickets" → all sources
   - Uses `TicketContextService` for data-driven answers (GROUP BY breakdowns, not hardcoded values), passing extracted source
   - Priority matching: uses case-insensitive partial matching — "urgent"/"critical" searches for priorities containing those words, falling back to showing the full priority breakdown
   - Status matching: same approach — data-driven, not hardcoded
   - Returns markdown-formatted answer or null

3. Wire fast path into `processMessage()` after workload fast path (after line 1453):
```java
// 2f. FAST PATH for ticket/CRM questions
String ticketAnswer = tryTicketFastPath(actualUserQuestion, connectionId);
if (ticketAnswer != null) {
    log.info("FAST PATH: Answered ticket question in {}ms", System.currentTimeMillis() - startTime);
    return buildFastPathResponse(connectionId, chatId, actualUserQuestion, ticketAnswer);
}
```

4. **CRITICAL: Anti-hallucination bypass for ticket questions** — THREE locations must be updated:

   **4a. Pre-LLM SQL mandate reminder** (`processMessage()` line 1625):
   ```java
   // Anti-hallucination: reinforce SQL requirement for data retrieval questions
   // BUT skip for ticket-only questions — they are answered from CRM context, not SQL.
   // Mixed questions ("users who filed urgent tickets") still get the SQL reminder.
   if (isDataRetrievalQuestion(actualUserQuestion) &&
       !ticketContextService.isTicketOnlyQuestion(actualUserQuestion)) {
       messagesToSend.add(new SystemMessage("REMINDER — DATA INTEGRITY MANDATE: ..."));
   }
   ```

   **4b. Post-LLM re-prompt enforcement** (line ~1681):
   ```java
   boolean isTicketOnly = ticketContextService.isTicketOnlyQuestion(actualUserQuestion);
   if (sql == null && !isTicketOnly && isDataRetrievalQuestion(actualUserQuestion)) {
   ```

   **4c. Streaming SQL mandate reminder** (`streamProcessMessage()` line 1996):
   ```java
   if (isDataRetrievalQuestion(actualUserQuestion) &&
       !ticketContextService.isTicketOnlyQuestion(actualUserQuestion)) {
       messagesToSend.add(new SystemMessage("REMINDER — DATA INTEGRITY MANDATE: ..."));
   }
   ```

   Uses `isTicketOnlyQuestion()` (strict) not `isTicketQuestion()` (broad). Mixed questions like "show me users who filed urgent tickets" still get SQL enforcement because they require database joins. Only pure ticket questions ("how many urgent tickets?") bypass it.

5. Add ticket context to LLM context path (after brain context, ~line 1484):
```java
String ticketContext = "";
if (neededContext.contains(ChatContextAssembler.ContextType.SUPPORT_TICKETS)) {
    String source = ticketContextService.extractSource(actualUserQuestion).orElse(null);
    ticketContext = contextAssembler.buildTicketContext(connectionId, source);
}
```

6. Update `buildSystemPromptFromTemplate()` (line 218) and `buildFallbackSystemPrompt()` (line 251):
   - Add `String ticketContext` as 9th parameter to BOTH methods
   - Add `params.put("ticketContext", ticketContext != null ? ticketContext : "")`
   - Update fallback concatenation
   - **3 call sites to update**:
     - `processMessage()` line 1540 (main path) — pass `ticketContext`
     - `streamProcessMessage()` line 1941 (streaming) — pass `ticketContext` (built from lightweight indexed queries, worth including)
     - `buildFallbackSystemPrompt()` call at line 243 (inside buildSystemPromptFromTemplate's catch) — pass through `ticketContext` parameter

7. Update `applyTokenBudget` call (line 1528) and result array unpacking:
   - Pass ticketContext as 8th param (before dbRules)
   - Unpack 7-element return array:
     ```java
     schemaContext = budgetedSections[0];
     classificationContext = budgetedSections[1];
     performanceContext = budgetedSections[2];
     brainContext = budgetedSections[3];
     feedbackContext = budgetedSections[4];
     trainingContext = budgetedSections[5];
     ticketContext = budgetedSections[6];  // NEW
     ```

8. **Streaming path** (`streamProcessMessage()` at line 1890):
   - Streaming currently skips classification, performance, and brain context for speed
   - **Ticket fast path in streaming**: Add the fast-path check immediately after `extractActualUserQuestion()` (line ~1893), BEFORE schema scan and RAG retrieval. If `tryTicketFastPath()` returns non-null, return `Flux.just(ticketAnswer)` and skip the entire pipeline. This must be early — placing it after schema scan + RAG would negate the "instant" benefit.
   - **Ticket context for complex streaming questions**: Build and pass `ticketContext` to `buildSystemPromptFromTemplate()` (unlike classification/performance/brain which involve heavy repo queries, ticket context is a few fast indexed COUNT/GROUP BY queries and is worth including)
   - **Anti-hallucination**: The SQL mandate reminder at line 1996 must also be guarded (see 4c above)
   - **No token budget call in streaming path**: Existing design choice, not changed in this PR. Ticket context is ~500-800 tokens which won't push streaming over limits.

### 5c. System Prompt Template

**Modify** `backend/src/main/resources/prompts/dba-system-prompt.st`

1. Add to CONTEXT-FIRST RESPONSE STRATEGY table (line 98):
```
Support tickets/CRM       | SUPPORT TICKET INTELLIGENCE section
```

2. Add `{ticketContext}` placeholder after `{performanceContext}` (around line 206):
```
{ticketContext}
```

3. Add to QUESTION CATEGORIES section:
```
[SUPPORT TICKET QUESTIONS]
Examples: "How many tickets?", "Urgent tickets?", "Recent support issues?"
- Check SUPPORT TICKET INTELLIGENCE section for ticket counts, priorities, statuses
- Reference ticket subjects for recent issues
- Do NOT generate SQL to query tickets — ticket data comes from linked CRM connections (HubSpot, etc.), not from database tables
- Use the provided context data directly to answer
```

4. Add RULE 5 override for ticket data in DATA INTEGRITY MANDATE:
```
RULE 5 — SUPPORT TICKET DATA:
Support ticket data (if present in SUPPORT TICKET INTELLIGENCE section) comes from
linked CRM systems (HubSpot, etc.), NOT from database tables. You may answer ticket
questions directly from this context without generating SQL.
```

## Phase 6: Frontend

**Modify** `src/components/ConnectionWizard/utils/constants.js`
- Add `crmDatabaseConnectionId: ''` to `DEFAULT_FORM_DATA` (line 340)

**Modify** `src/components/ConnectionWizard/steps/CrmCredentialsStep.js`
- Import `connectionAPI` from `@/lib/api/client` and `useQuery` from TanStack
- Add database connection dropdown above connection name field
- Fetch connections via `useQuery({ queryKey: ['connections'], queryFn: connectionAPI.getAllConnections })`
- Options: "None (link later)" + list of database connections showing `{connectionName} ({dbType})`
- Updates `formData.crmDatabaseConnectionId`

**Modify** `src/components/ConnectionWizard/hooks/useConnectionWizard.js`
- Update `getCrmPayload()` (line 333) to include `databaseConnectionId: formData.crmDatabaseConnectionId || null`
- Add to dependency array

**Modify** `src/components/ManageConnectionsModal.js`
- In `CrmRow` component: accept `connections` prop (the DB connections list already loaded in parent)
- Show linked database name: cross-reference `crm.databaseConnectionId` with connections list
- Display as a small label below the CRM connection name, e.g., "Linked to: aws-rds-master"

---

## Files Summary

| File | Action |
|------|--------|
| `backend/src/main/resources/db/migration/V62__add_database_connection_id_to_crm_connections.sql` | Create |
| `backend/src/main/java/com/dbaagent/ticket/model/CrmConnection.java` | Modify (add field) |
| `backend/src/main/java/com/dbaagent/ticket/repository/CrmConnectionRepository.java` | Modify (add finder) |
| `backend/src/main/java/com/dbaagent/ticket/repository/SupportTicketRepository.java` | Modify (add GROUP BY queries + count methods) |
| `backend/src/main/java/com/dbaagent/ticket/service/TicketContextService.java` | Create |
| `backend/src/main/java/com/dbaagent/ticket/service/CrmConnectionService.java` | Modify (add param + methods) |
| `backend/src/main/java/com/dbaagent/ticket/controller/CrmConnectionController.java` | Modify (accept/return new field) |
| `backend/src/main/java/com/dbaagent/service/ChatContextAssembler.java` | Modify (enum, detection, buildTicketContext, applyTokenBudget) |
| `backend/src/main/java/com/dbaagent/service/ChatService.java` | Modify (fast path, anti-hallucination guard, context wiring, prompt builders) |
| `backend/src/main/resources/prompts/dba-system-prompt.st` | Modify (add ticket sections + rules) |
| `src/components/ConnectionWizard/utils/constants.js` | Modify (add form field) |
| `src/components/ConnectionWizard/steps/CrmCredentialsStep.js` | Modify (add dropdown) |
| `src/components/ConnectionWizard/hooks/useConnectionWizard.js` | Modify (update payload) |
| `src/components/ManageConnectionsModal.js` | Modify (show linked DB, pass connections to CrmRow) |
| `backend/src/test/java/com/dbaagent/ticket/service/TicketContextServiceTest.java` | Create |
| `backend/src/test/java/com/dbaagent/service/ChatContextAssemblerTicketTest.java` | Create |
| `backend/src/test/java/com/dbaagent/service/ChatServiceTicketTest.java` | Create |

## Robustness Considerations

1. **Data-driven, not hardcoded**: Priority/status breakdowns use native SQL GROUP BY queries (JPQL doesn't support LIMIT) to discover actual values in the database. No assumptions about "HIGH"/"MEDIUM"/"LOW" or "OPEN"/"CLOSED" — HubSpot stores raw `hs_ticket_priority` and pipeline stage names. GROUP BY results capped at 20 rows via `LIMIT 20` in native queries.

2. **Anti-hallucination bypass (3 locations)**: Ticket-only questions (pure CRM queries with no database dimension) are excluded from SQL enforcement at all three injection points via `isTicketOnlyQuestion()`: the pre-LLM SQL mandate reminder (processMessage L1625), the post-LLM re-prompt enforcement (L1681), and the streaming SQL reminder (streamProcessMessage L1996). Mixed questions ("users who filed tickets") still get full SQL enforcement.

3. **Token budget as first-class section**: `ticketContext` gets its own truncation slot (lowest priority — truncated first before training data). Not appended to `performanceContext` which would lose truncation control. Return array changes from 6 to 7 elements — all unpacking code updated.

4. **Prompt template explicit ticket rule**: The system prompt explicitly tells the LLM that ticket data comes from CRM context, not database tables, preventing SQL generation attempts for ticket queries.

5. **Graceful degradation**: If no CRM connections are linked, `hasCrmConnections()` returns false, fast path returns null, and context is empty string. The LLM sees no ticket context and can't hallucinate ticket data.

6. **Priority fuzzy matching**: The fast path's priority extraction uses case-insensitive partial matching ("urgent" matches any priority containing "urgent"). Falls back to showing the full breakdown when no specific priority matches.

7. **Empty IN clause guard**: All `TicketContextService` methods check `if (crmIds.isEmpty()) return <default>` before passing to JPA repository methods. Spring Data JPA can generate invalid SQL (`WHERE x IN ()`) with empty lists.

8. **Security (defense-in-depth)**: `create()`/`update()` are already ADMIN-only (`@PreAuthorize`). As defense-in-depth, validates that `databaseConnectionId` exists in `encrypted_credentials` before linking (prevents stale UUID references).

9. **Streaming path**: Ticket fast path returns `Flux.just(answer)` for deterministic ticket questions (instant, no LLM). For complex ticket questions, ticket context IS built and passed (unlike classification/performance/brain which are skipped in streaming — ticket context is lightweight indexed queries). Anti-hallucination SQL reminder also guarded at L1996.

10. **Backward compatibility**: `buildSystemPromptFromTemplate()` gains a 9th param — all 3 call sites (processMessage, streamProcessMessage, fallback) updated. `applyTokenBudget()` gains an 8th param and returns 7 elements — the single caller's array unpacking updated.

11. **Cascade delete behavior**: `ON DELETE SET NULL` — when a database connection is deleted, linked CRM connections become unlinked (`database_connection_id = NULL`) but are NOT deleted. Their tickets remain accessible via the CRM connections list.

## Phase 7: Unit Tests

**Create** `backend/src/test/java/com/dbaagent/ticket/service/TicketContextServiceTest.java`
- Test `getCrmConnectionIdsForDatabase()` with 0, 1, N CRM connections
- Test `hasCrmConnections()` returns false for unlinked DB, true for linked
- Test `countTicketsForDatabase()` returns 0 when no CRM connections (empty IN guard)
- Test `isTicketQuestion()` with positive matches ("How many tickets", "urgent hubspot issues") and negative matches ("How many tables", "show me users")
- Test `isTicketOnlyQuestion()` returns true for "How many urgent tickets?" and false for "show me users who filed urgent tickets" (mixed question with DB join intent)
- Test `extractSource()` returns "HUBSPOT" for "hubspot tickets", "ZENDESK" for "zendesk issues", empty for "how many tickets"
- Test priority/status breakdown returns correct Map from mock `Object[]` results

**Create** `backend/src/test/java/com/dbaagent/service/ChatContextAssemblerTicketTest.java`
- Test `determineNeededContext()` includes `SUPPORT_TICKETS` for ticket keywords
- Test `determineNeededContext()` does NOT include `SUPPORT_TICKETS` for DB-only questions
- Test `buildTicketContext()` returns `""` when no CRM connections linked
- Test `buildTicketContext()` returns formatted output with priority/status breakdowns
- Test `applyTokenBudget()` returns 7-element array (this method lives in ChatContextAssembler)
- Test `applyTokenBudget()` truncates tickets section first when over budget

**Create** `backend/src/test/java/com/dbaagent/service/ChatServiceTicketTest.java`
- Test observable behavior via `processMessage()`: ticket question with linked CRM returns fast-path response (no SQL in result, success=true, message contains ticket count)
- Test observable behavior via `processMessage()`: "Top 5 tickets" does NOT produce SQL in response (verifies anti-hallucination bypass works end-to-end)
- Test observable behavior via `processMessage()`: mixed question "users who filed urgent tickets" DOES still produce SQL (anti-hallucination not bypassed for mixed questions)
- Test observable behavior via `processMessage()`: non-ticket question is unaffected (still goes through normal LLM path)
- Test source filtering: "How many hubspot tickets?" with both HUBSPOT and ZENDESK CRM connections linked — response should only count HUBSPOT tickets

---

## Verification (Manual)

1. **Migration**: `mvn spring-boot:run` — V62 applies, `crm_connections` has `database_connection_id` column
2. **Create CRM connection**: POST `/crm-connections` with `databaseConnectionId` — saved correctly
3. **Chat fast path**: Ask "How many urgent hubspot tickets?" on the linked DB connection — returns count with breakdown
4. **Chat context path**: Ask "Summarize our support ticket trends" — LLM sees ticket context and responds
5. **Anti-hallucination**: Ask "Top 5 urgent tickets" — should NOT force SQL generation, should use ticket context
6. **Streaming fast path**: Ask "How many tickets?" via streaming — returns `Flux.just(answer)` instantly
7. **Frontend**: Open Add Connection → CRM → see database connection dropdown; ManageConnectionsModal shows linked DB name
8. **No CRM link**: Asking ticket questions on a DB with no linked CRM connections falls through gracefully
9. **Token budget**: Verify ticket context is truncated first when system prompt is over token limit
10. **Lint**: `npm run lint` passes for frontend changes
11. **Unit tests**: `mvn test -Dtest="TicketContextServiceTest,ChatContextAssemblerTicketTest,ChatServiceTicketTest"` passes
