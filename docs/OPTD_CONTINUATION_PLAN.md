# OPTD Sidecar Continuation Plan

## Current Status Summary

### What's Working
- ✅ Sidecar service running on port 8088
- ✅ Backend integration with `OptdOptimizationService` and `OptdClient`
- ✅ Candidate storage in `QueryOptimizationCandidateRun` table
- ✅ Simple queries (bc3bb9, e1c327) successfully planned with cost estimates
- ✅ Benchmarking works for queries that can be executed
- ✅ AI_REWRITE candidates get plan signatures for some complex queries

### Fixed
- ✅ MySQL optimizer hints (STRAIGHT_JOIN, FORCE/USE/IGNORE INDEX/KEY) now stripped before DataFusion parsing
  - Java: `stripMySqlHints()` in `OptdOptimizationService` (runs in normalization pipeline and regex fallback)
  - Rust: `strip_mysql_hints()` in sidecar `main.rs` (runs for MySQL queries before alias extraction)

### Current Issues (ff42b05482a7d8e7402b92afce5fe803)
1. **ORIGINAL candidate fails optd planning**
   - Parse error: "Expected: ), found: . at Line: 1, Column: 2689"
   - Schema error: "No field named be_booking_refund.booking_id"
   - Note: `be_booking_refund` is NOT in the original query - phantom reference

2. **AI_REWRITE not benchmarked**
   - planSignature exists (cost=12.0)
   - medianMs is null - benchmark was skipped or failed
   - Likely because the AI rewrite isn't executable or uses placeholders

3. **Alias/Column Resolution Bug**
   - The sidecar is trying to add a column from a table that doesn't exist in the query
   - Suggests the `alias_map` or `seed_schema_from_query` is picking up unrelated tables

## Implementation Tasks

### Phase 1: Diagnostic Logging (Immediate)

#### Task 1.1: Add Query Logging to OptdClient
Add logging in the backend to capture the exact SQL being sent to optd.

**File:** `backend/src/main/java/com/dbaagent/service/optd/OptdClient.java`
```java
// Add before the HTTP call:
log.info("Sending to optd - dbType: {}, queryLength: {}, schemaTableCount: {}",
    request.getDbType(),
    request.getQuery().length(),
    request.getSchema().getTables().size());
log.debug("Optd query payload: {}", request.getQuery().substring(0, Math.min(500, request.getQuery().length())));
```

#### Task 1.2: Add Aggressive Fallback Logging
Log when aggressive fallback is triggered and what query it produces.

**File:** `backend/src/main/java/com/dbaagent/service/optd/OptdOptimizationService.java`
```java
// In optimizeQuery(), when aggressive fallback is used:
log.warn("optd aggressive fallback triggered for connectionId: {}", connectionId);
log.debug("Aggressive fallback query: {}", aggressive.substring(0, Math.min(500, aggressive.length())));
```

#### Task 1.3: Add Sidecar Request Logging
Log the full query and schema in the sidecar for debugging.

**File:** `optd/optd-sidecar/src/main.rs`
```rust
// In optimize_internal, at the start:
info!("Processing query (first 500 chars): {}", &query[..query.len().min(500)]);
info!("Schema tables: {:?}", schema.tables.iter().map(|t| &t.name).collect::<Vec<_>>());
```

### Phase 2: Fix Column Qualification Bug

#### Task 2.1: Investigate Phantom Table Reference
The error "No field named be_booking_refund.booking_id" indicates the sidecar is trying to qualify a column with a table that wasn't in the original query.

**Hypothesis:** The `seed_schema_from_query` or `extract_alias_map` functions in `main.rs` are matching against partial table names incorrectly.

**Investigation:**
1. Add logging to `seed_schema_from_query` to show what table-column pairs it detects
2. Add logging to `remap_column_with_alias` to show alias resolution attempts
3. Check if `be_booking_refund` appears in the schema metadata being sent

**File:** `optd/optd-sidecar/src/main.rs`
```rust
// In seed_schema_from_query:
info!("Seeding schema from query - found table.column: {}.{}", table, column);

// In remap_column_with_alias:
info!("Remapping column {} with alias_map: {:?} -> result: {:?}", raw_column, alias_map, result);
```

#### Task 2.2: Fix Unique Table Column Resolution
The `find_unique_table_with_column` function should only return a table if exactly one table in the schema has that column. If it's returning `be_booking_refund`, that table must be in the schema.

**Fix:** Ensure the schema being passed to optd only includes tables actually referenced in the query.

### Phase 3: Fix Benchmark Skipping

#### Task 3.1: Investigate Why AI_REWRITE Isn't Benchmarked
The AI_REWRITE candidate has a plan signature but no benchmark results. Check:

1. Is the `candidateSql` for AI_REWRITE executable?
2. Does it contain placeholders that prevent execution?
3. Is there an error during benchmark that isn't being captured?

**File:** `backend/src/main/java/com/dbaagent/service/OptimizationBenchmarkService.java`
```java
// Add logging before benchmark attempt:
log.info("Attempting benchmark for candidateId: {}, sqlLength: {}",
    candidate.getCandidateId(),
    candidate.getCandidateSql().length());
```

#### Task 3.2: Add Benchmark Error Capture
Ensure benchmark errors are captured and stored.

```java
// In benchmark method, capture and store errors:
catch (Exception e) {
    log.warn("Benchmark failed for {}: {}", candidate.getCandidateId(), e.getMessage());
    candidate.setStatus("ERROR");
    candidate.setErrorMessage(e.getMessage());
}
```

### Phase 4: Test Suite

#### Test Case 1: Re-run ff42 Fingerprint
```bash
CONN=4ab3b55e-bacd-4af6-bcf5-f0716ed5e4a3
FP=ff42b05482a7d8e7402b92afce5fe803

# Get candidates (triggers optd if not cached)
curl -s "http://localhost:8080/api/slow-queries/optimize/candidates/$CONN/$FP" | python3 -m json.tool

# Check sidecar logs for the query being processed
tail -50 /tmp/optd-sidecar.log

# Trigger benchmark
curl -s -X POST "http://localhost:8080/api/slow-queries/optimize/benchmark/$CONN/$FP" \
  -H 'content-type: application/json' \
  -d '{"runs":3,"timeoutMs":30000}' | python3 -m json.tool
```

#### Test Case 2: Verify Working Fingerprints Still Work
```bash
# Test bc3bb9 (should have ORIGINAL cost 5.0, AI_REWRITE cost 1.0)
curl -s "http://localhost:8080/api/slow-queries/optimize/candidates/$CONN/bc3bb9..." | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('candidates', []):
    print(f\"{c['candidateId']}: cost={c.get('estimatedCost')}, medianMs={c.get('medianMs')}\")
"
```

#### Test Case 3: Direct Sidecar Test
Test the sidecar directly with a minimal query to verify it's working:

```bash
curl -s -X POST http://localhost:8088/v1/optimize \
  -H 'content-type: application/json' \
  -d '{
    "db_type": "mysql",
    "query": "SELECT id, name FROM users WHERE id = 1",
    "schema": {
      "tables": [
        {
          "name": "users",
          "columns": [
            {"name": "id", "data_type": "int", "nullable": false},
            {"name": "name", "data_type": "varchar", "nullable": true}
          ],
          "row_count": 1000
        }
      ]
    }
  }' | python3 -m json.tool
```

### Phase 5: Frontend Integration Test

#### Task 5.1: Test QueryDetailDialog
1. Navigate to Slow Query Analysis tab
2. Select a query with fingerprint ff42...
3. Click to open detail dialog
4. Verify "Best Recommended Plan" section shows:
   - If ORIGINAL has benchmark: shows "fastest measured" badge
   - If only AI_REWRITE has cost: shows "predicted fastest" badge
5. Verify "Benchmark candidates" button triggers benchmark

### Phase 6: Error Recovery Improvements

#### Task 6.1: Add SELECT 1 Fallback
When both normal and aggressive fallback fail, try a minimal "SELECT 1" fallback that preserves only the FROM/JOIN structure.

**File:** `backend/src/main/java/com/dbaagent/service/optd/OptdOptimizationService.java`
```java
// After aggressive fallback fails:
if (response.isEmpty()) {
    String minimal = buildMinimalPlannerQuery(query, schemaName);
    if (minimal != null) {
        OptdOptimizeRequest minimalRequest = OptdOptimizeRequest.builder()
            .dbType(dbType)
            .query(minimal)
            .schema(optdSchema)
            .build();
        response = optdClient.optimize(minimalRequest);
        if (response.isPresent()) {
            log.info("optd succeeded with minimal query fallback");
        }
    }
}
```

#### Task 6.2: Improve Schema Filtering
Only include tables that are actually referenced in the query to avoid phantom column references.

```java
// In mapSchemaForQuery:
Set<String> referenced = extractReferencedTables(query);
// Filter schema.tables to only include referenced tables
```

## Priority Order

1. **Immediate (Today):**
   - Task 1.1-1.3: Add diagnostic logging
   - Task 2.1: Investigate phantom table reference

2. **Next Session:**
   - Task 2.2: Fix column resolution
   - Task 3.1-3.2: Fix benchmark skipping
   - Phase 4: Run test suite

3. **Follow-up:**
   - Task 6.1-6.2: Error recovery improvements
   - Phase 5: Frontend integration test

## Commands to Resume

```bash
# 1) Check processes
pgrep -fl optd-sidecar && pgrep -fl spring-boot:run

# 2) If not running, start services:
cd /Users/geekypunk/sasank/stayflexi/optd
nohup cargo run -p optd-sidecar > /tmp/optd-sidecar.log 2>&1 &

cd /Users/geekypunk/sasank/stayflexi/dba-agent/backend
nohup mvn -q -DskipTests spring-boot:run > /tmp/dba-agent.log 2>&1 &

# 3) Watch logs
tail -f /tmp/optd-sidecar.log
tail -f /tmp/dba-agent.log

# 4) Test candidates endpoint
curl -s "http://localhost:8080/api/slow-queries/optimize/candidates/4ab3b55e-bacd-4af6-bcf5-f0716ed5e4a3/ff42b05482a7d8e7402b92afce5fe803" | python3 -m json.tool
```
