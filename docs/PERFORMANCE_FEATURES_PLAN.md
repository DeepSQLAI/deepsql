# Performance Features - Implementation Plan

## Overview

Implementing two critical performance analysis features:
1. **EXPLAIN Plan Analysis** - Visualize and optimize query execution plans
2. **Slow Query Analysis** - Identify and fix slow queries automatically

**Target:** Both MySQL and PostgreSQL
**Timeline:** 2-3 weeks
**Value:** Deep performance insights for DBAs

---

## Feature 1: EXPLAIN Plan Analysis

### What It Does

- Execute EXPLAIN on any SQL query
- Parse execution plan into structured data
- Identify performance issues (full table scans, missing indexes, etc.)
- Visualize execution plan as interactive tree
- Provide AI-powered optimization suggestions

### Architecture

#### Backend Components

**1. Models:**
```java
// ExplainPlanNode.java - Represents one node in execution plan
{
  "nodeType": "Seq Scan",           // Type of operation
  "tableName": "users",
  "rows": 100000,                    // Estimated rows
  "cost": 2542.00,                   // Query cost
  "actualRows": 98543,               // Actual rows (if ANALYZE)
  "actualTime": 145.234,             // Actual execution time
  "children": [...]                  // Child nodes
}

// ExplainPlanAnalysis.java - Complete analysis
{
  "query": "SELECT * FROM users WHERE email = ?",
  "dbType": "postgresql",
  "planTree": ExplainPlanNode,
  "issues": [
    {
      "severity": "HIGH",
      "type": "FULL_TABLE_SCAN",
      "message": "Sequential scan on 100K row table",
      "recommendation": "CREATE INDEX idx_users_email ON users(email)"
    }
  ],
  "estimatedCost": 2542.00,
  "actualCost": 2489.12,
  "executionTimeMs": 145.234,
  "aiSuggestions": "..."
}
```

**2. Service - ExplainPlanService.java:**
```java
// Core methods:
- analyzeQuery(connectionId, sql) → ExplainPlanAnalysis
- parsePostgresExplain(jsonResult) → ExplainPlanNode
- parseMySQLExplain(tableResult) → ExplainPlanNode
- detectIssues(planNode) → List<PerformanceIssue>
- generateOptimizationSQL(issues) → List<String>
```

**3. Controller - ExplainController.java:**
```java
POST /api/explain/analyze
{
  "connectionId": "...",
  "query": "SELECT ...",
  "analyzeMode": true  // Use EXPLAIN ANALYZE (actual execution)
}

Response:
{
  "planTree": {...},
  "issues": [...],
  "estimatedCost": 2542.00,
  "executionTimeMs": 145.234,
  "optimizationSuggestions": [...]
}
```

#### Frontend Components

**1. ExplainPlanTab.js:**
- SQL input area
- Execute EXPLAIN button
- Plan visualization (tree/table view toggle)
- Issue list with severity indicators
- Optimization suggestions with copy buttons

**2. ExplainTreeVisualization.js:**
- Interactive D3.js tree diagram
- Color-coded nodes (green=fast, red=slow)
- Click to expand/collapse
- Hover for details
- Cost/time annotations

**3. Integration in SQL Runner:**
- Add "Explain" button next to "Run Query"
- Show execution plan in results pane
- Compare before/after optimization

### MySQL Implementation

**EXPLAIN Output Format:**
```
+----+-------------+-------+------+---------------+------+---------+------+-------+-------------+
| id | select_type | table | type | possible_keys | key  | key_len | ref  | rows  | Extra       |
+----+-------------+-------+------+---------------+------+---------+------+-------+-------------+
|  1 | SIMPLE      | users | ALL  | NULL          | NULL | NULL    | NULL | 100000| Using where |
+----+-------------+-------+------+---------------+------+---------+------+-------+-------------+
```

**Key Indicators:**
- `type = ALL` → Full table scan (BAD)
- `type = index` → Full index scan (BAD for large tables)
- `type = ref` → Index lookup (GOOD)
- `type = const` → Primary key lookup (EXCELLENT)
- `Extra = Using filesort` → Sorting needed (potential issue)
- `Extra = Using temporary` → Temp table created (slow)

**Parsing Strategy:**
```java
private ExplainPlanNode parseMySQLExplain(List<Map<String, Object>> rows) {
    ExplainPlanNode root = new ExplainPlanNode();
    for (Map<String, Object> row : rows) {
        String type = (String) row.get("type");
        Integer rows = (Integer) row.get("rows");

        if ("ALL".equals(type) && rows > 10000) {
            addIssue("FULL_TABLE_SCAN", HIGH, ...);
        }
        // ... more parsing logic
    }
}
```

### PostgreSQL Implementation

**EXPLAIN Output Format (JSON):**
```json
{
  "Plan": {
    "Node Type": "Seq Scan",
    "Relation Name": "users",
    "Alias": "users",
    "Startup Cost": 0.00,
    "Total Cost": 2542.00,
    "Plan Rows": 100000,
    "Plan Width": 128,
    "Actual Startup Time": 0.045,
    "Actual Total Time": 145.234,
    "Actual Rows": 98543,
    "Filter": "(email = 'test@example.com')",
    "Rows Removed by Filter": 1457
  }
}
```

**Key Indicators:**
- `Node Type = Seq Scan` → Full table scan (potentially bad)
- `Node Type = Index Scan` → Using index (good)
- `Node Type = Index Only Scan` → Covered index (excellent)
- `Rows Removed by Filter` → Poor selectivity (needs better index)
- High `Total Cost` → Expensive operation

**Parsing Strategy:**
```java
private ExplainPlanNode parsePostgresExplain(Map<String, Object> json) {
    Map<String, Object> plan = (Map<String, Object>) json.get("Plan");

    ExplainPlanNode node = new ExplainPlanNode();
    node.setNodeType((String) plan.get("Node Type"));
    node.setCost((Double) plan.get("Total Cost"));

    // Recursive parsing for nested plans
    if (plan.containsKey("Plans")) {
        List<Map<String, Object>> childPlans =
            (List<Map<String, Object>>) plan.get("Plans");
        for (Map<String, Object> childPlan : childPlans) {
            node.addChild(parsePostgresExplain(childPlan));
        }
    }

    return node;
}
```

### Issue Detection Rules

**Full Table Scan:**
```java
if (nodeType.contains("Seq Scan") || type.equals("ALL")) {
    if (estimatedRows > 10000) {
        return new PerformanceIssue(
            HIGH,
            "FULL_TABLE_SCAN",
            String.format("Sequential scan on %,d row table '%s'",
                         estimatedRows, tableName),
            String.format("CREATE INDEX idx_%s_%s ON %s(%s)",
                         tableName, extractColumn(filter), tableName, extractColumn(filter))
        );
    }
}
```

**Missing Index on Join:**
```java
if (nodeType.contains("Nested Loop") && !hasIndexAccess(childNodes)) {
    return new PerformanceIssue(
        CRITICAL,
        "UNINDEXED_JOIN",
        "Join without index will be very slow",
        "CREATE INDEX ON join_table(join_column)"
    );
}
```

**Large Sort Operation:**
```java
if (extra.contains("Using filesort") || nodeType.contains("Sort")) {
    if (estimatedRows > 100000) {
        return new PerformanceIssue(
            MEDIUM,
            "LARGE_SORT",
            String.format("Sorting %,d rows in memory", estimatedRows),
            "Consider adding index on ORDER BY columns"
        );
    }
}
```

### UI Design

**Plan Tree Visualization:**
```
┌─────────────────────────────────────────┐
│ SELECT * FROM users WHERE email = ?     │
│ [Explain Query] [Explain Analyze]       │
└─────────────────────────────────────────┘

Execution Plan Tree:
┌─────────────────────────────────────────┐
│ ⚠️  Seq Scan on users                   │
│     Cost: 2542.00                       │
│     Rows: 100,000                       │
│     Time: 145.23ms                      │
│     ├─ Filter: (email = ?)              │
│     └─ Rows Removed: 1,457              │
└─────────────────────────────────────────┘

Performance Issues Found: 2

🔴 HIGH: Full Table Scan
Sequential scan on 100,000 row table 'users'
Recommendation: CREATE INDEX idx_users_email ON users(email)
[Copy SQL]

🟡 MEDIUM: Poor Filter Selectivity
Only 1.4% of rows filtered, consider more selective condition
```

---

## Feature 2: Slow Query Analysis

### What It Does

- Parse MySQL slow query log or PostgreSQL pg_stat_statements
- Identify top 10 slowest queries
- Calculate metrics: avg time, total time, call count
- Suggest optimizations (indexes, query rewrites)
- Track performance improvements over time

### Architecture

#### Backend Components

**1. Models:**
```java
// SlowQuery.java - Represents one slow query
{
  "queryId": "hash-of-normalized-query",
  "queryText": "SELECT * FROM users WHERE email = ?",
  "normalizedQuery": "SELECT * FROM users WHERE email = ?",  // Literals removed
  "database": "production_db",
  "avgExecutionTimeMs": 234.56,
  "maxExecutionTimeMs": 1245.00,
  "minExecutionTimeMs": 89.12,
  "totalExecutionTimeMs": 12345678.90,
  "callCount": 52634,
  "rowsExamined": 5263400,
  "rowsSent": 52634,
  "firstSeen": "2025-12-20T10:00:00Z",
  "lastSeen": "2025-12-23T15:30:00Z",
  "affectedTables": ["users"],
  "hasIndex": false,
  "suggestedIndexes": [...]
}

// SlowQueryAnalysis.java - Complete analysis
{
  "connectionId": "...",
  "analysisDate": "2025-12-23T15:30:00Z",
  "timeRange": "LAST_24_HOURS",
  "topSlowQueries": [...],
  "totalQueriesAnalyzed": 125643,
  "totalSlowQueries": 342,
  "slowQueryThresholdMs": 100,
  "recommendations": [...]
}
```

**2. Service - SlowQueryService.java:**

**For MySQL (via slow query log):**
```java
// Methods:
- analyzeSlowLog(connectionId, timeRange) → SlowQueryAnalysis
- parseSlowLogFile(filePath) → List<SlowQuery>
- normalizeQuery(sql) → String
- detectMissingIndexes(slowQuery) → List<IndexRecommendation>
```

**For PostgreSQL (via pg_stat_statements):**
```java
// Methods:
- analyzeStatStatements(connectionId, timeRange) → SlowQueryAnalysis
- queryStatStatements(connection) → List<SlowQuery>
- calculateMetrics(statRow) → SlowQuery
```

**3. Controller - SlowQueryController.java:**
```java
GET /api/slow-queries/analyze/{connectionId}
?timeRange=LAST_24_HOURS
&threshold=100

Response:
{
  "topSlowQueries": [...],
  "totalQueriesAnalyzed": 125643,
  "recommendations": [...]
}

GET /api/slow-queries/top/{connectionId}
?limit=10

POST /api/slow-queries/optimize/{queryId}
// Returns optimization suggestions
```

#### Frontend Components

**1. SlowQueryAnalysisTab.js:**
- Time range selector (Last Hour, 24h, 7d, 30d)
- Threshold slider (50ms - 1000ms)
- Top slow queries table
- Query details modal
- Optimization suggestions

**2. SlowQueryTable.js:**
- Sortable columns (time, calls, rows examined)
- Color-coded severity
- Expand row for query text
- "Optimize" button per query

### MySQL Implementation

**Enable Slow Query Log:**
```sql
-- Check if enabled
SHOW VARIABLES LIKE 'slow_query_log';

-- Enable it
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 0.1;  -- 100ms threshold
SET GLOBAL slow_query_log_file = '/var/log/mysql/slow.log';
```

**Query pg_stat_statements Alternative (if available):**
```sql
-- MySQL 5.7+ Performance Schema
SELECT
    DIGEST_TEXT as query,
    COUNT_STAR as call_count,
    AVG_TIMER_WAIT/1000000000 as avg_time_ms,
    MAX_TIMER_WAIT/1000000000 as max_time_ms,
    SUM_ROWS_EXAMINED as rows_examined,
    SUM_ROWS_SENT as rows_sent
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'your_database'
ORDER BY AVG_TIMER_WAIT DESC
LIMIT 10;
```

**Parse Slow Log File:**
```java
private List<SlowQuery> parseMySQLSlowLog(String logContent) {
    List<SlowQuery> queries = new ArrayList<>();

    Pattern pattern = Pattern.compile(
        "# Time: (\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}).*?" +
        "# Query_time: ([\\d.]+)\\s+Lock_time: ([\\d.]+)\\s+Rows_sent: (\\d+)\\s+Rows_examined: (\\d+).*?" +
        "SET timestamp=\\d+;\\s*([^#]+)",
        Pattern.DOTALL
    );

    Matcher matcher = pattern.matcher(logContent);
    while (matcher.find()) {
        SlowQuery query = new SlowQuery();
        query.setTimestamp(matcher.group(1));
        query.setQueryTime(Double.parseDouble(matcher.group(2)) * 1000); // to ms
        query.setRowsSent(Integer.parseInt(matcher.group(4)));
        query.setRowsExamined(Integer.parseInt(matcher.group(5)));
        query.setQueryText(matcher.group(6).trim());
        queries.add(query);
    }

    return queries;
}
```

### PostgreSQL Implementation

**Enable pg_stat_statements:**
```sql
-- Add to postgresql.conf
shared_preload_libraries = 'pg_stat_statements'
pg_stat_statements.track = all
pg_stat_statements.max = 10000

-- Restart PostgreSQL, then:
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

**Query Slow Queries:**
```sql
SELECT
    queryid,
    query,
    calls,
    total_exec_time / 1000 as total_time_ms,
    mean_exec_time as avg_time_ms,
    max_exec_time as max_time_ms,
    min_exec_time as min_time_ms,
    rows,
    100.0 * shared_blks_hit / NULLIF(shared_blks_hit + shared_blks_read, 0) AS cache_hit_ratio
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
    AND mean_exec_time > 100  -- Threshold: 100ms
ORDER BY total_exec_time DESC
LIMIT 10;
```

**Service Implementation:**
```java
public SlowQueryAnalysis analyzePostgresSlowQueries(
    String connectionId,
    TimeRange timeRange,
    double thresholdMs
) {
    String sql = """
        SELECT
            queryid,
            query,
            calls,
            total_exec_time / 1000 as total_time_ms,
            mean_exec_time as avg_time_ms,
            max_exec_time as max_time_ms,
            rows
        FROM pg_stat_statements
        WHERE mean_exec_time > ?
        ORDER BY total_exec_time DESC
        LIMIT 50
    """;

    List<SlowQuery> slowQueries = jdbcTemplate.query(sql,
        new Object[]{thresholdMs},
        (rs, rowNum) -> {
            SlowQuery query = new SlowQuery();
            query.setQueryId(rs.getString("queryid"));
            query.setQueryText(rs.getString("query"));
            query.setCallCount(rs.getLong("calls"));
            query.setTotalExecutionTimeMs(rs.getDouble("total_time_ms"));
            query.setAvgExecutionTimeMs(rs.getDouble("avg_time_ms"));
            query.setMaxExecutionTimeMs(rs.getDouble("max_time_ms"));
            query.setRowsSent(rs.getLong("rows"));
            return query;
        }
    );

    // Generate recommendations for each slow query
    for (SlowQuery query : slowQueries) {
        ExplainPlanAnalysis explainAnalysis =
            explainPlanService.analyzeQuery(connectionId, query.getQueryText());
        query.setSuggestedIndexes(explainAnalysis.getIndexRecommendations());
    }

    return new SlowQueryAnalysis(slowQueries);
}
```

### Optimization Suggestions

**1. Missing Index Detection:**
```java
// For each slow query, run EXPLAIN and check for full scans
ExplainPlanAnalysis plan = explainPlanService.analyzeQuery(connectionId, query.getQueryText());
if (plan.hasFullTableScans()) {
    List<String> indexes = plan.getIssues().stream()
        .filter(i -> i.getType() == IssueType.FULL_TABLE_SCAN)
        .map(i -> i.getRecommendation())
        .collect(Collectors.toList());
    query.setSuggestedIndexes(indexes);
}
```

**2. Query Rewrite Suggestions:**
```java
// Detect SELECT *
if (query.getQueryText().contains("SELECT *")) {
    query.addSuggestion("Avoid SELECT *, specify only needed columns");
}

// Detect OR conditions
if (query.getQueryText().contains(" OR ")) {
    query.addSuggestion("Consider UNION instead of OR for better index usage");
}

// Detect NOT IN
if (query.getQueryText().contains("NOT IN")) {
    query.addSuggestion("Replace NOT IN with LEFT JOIN ... WHERE ... IS NULL");
}
```

**3. AI-Powered Optimization:**
```java
// Use GPT-4 to suggest query rewrites
String prompt = String.format(
    "Optimize this %s query:\n%s\n\n" +
    "Current performance:\n" +
    "- Avg time: %.2fms\n" +
    "- Rows examined: %,d\n" +
    "- Rows sent: %,d\n\n" +
    "Provide optimized version and explain changes.",
    dbType, query.getQueryText(),
    query.getAvgExecutionTimeMs(),
    query.getRowsExamined(),
    query.getRowsSent()
);

String optimization = openAIClient.getChatCompletion(prompt);
query.setAiOptimization(optimization);
```

### UI Design

**Slow Query Analysis Tab:**
```
┌─────────────────────────────────────────────────────────────┐
│ Slow Query Analysis                                         │
│                                                             │
│ Time Range: [Last 24 Hours ▼]  Threshold: [100ms ▼]       │
│ [Analyze Queries]                                           │
└─────────────────────────────────────────────────────────────┘

Top 10 Slowest Queries (by total time)

┌─────────────────────────────────────────────────────────────┐
│ #1 - 🔴 CRITICAL (52,634 calls, 3.42 hours total)          │
│ Avg: 234.56ms | Max: 1,245ms | Rows Examined: 100K/call   │
│                                                             │
│ SELECT * FROM users WHERE email = 'test@example.com'       │
│                                                             │
│ Issues:                                                     │
│ • Full table scan on 100K row table                        │
│ • Using SELECT * instead of specific columns               │
│                                                             │
│ Recommendations:                                            │
│ ✅ CREATE INDEX idx_users_email ON users(email) [Copy]     │
│ ✅ Replace SELECT * with SELECT id, name, email [Copy]     │
│                                                             │
│ AI Suggestion: "This query scans the entire users table... │
│ [View Full Optimization]                                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ #2 - 🟡 HIGH (1,234 calls, 1.15 hours total)               │
│ ...                                                         │
└─────────────────────────────────────────────────────────────┘
```

---

## Implementation Timeline

### Week 1: EXPLAIN Plan Analysis

**Day 1-2: Backend**
- [ ] Create models (ExplainPlanNode, ExplainPlanAnalysis, PerformanceIssue)
- [ ] Implement ExplainPlanService with MySQL parser
- [ ] Implement PostgreSQL JSON parser
- [ ] Add issue detection rules
- [ ] Create ExplainController endpoints

**Day 3-4: Frontend**
- [ ] Create ExplainPlanTab component
- [ ] Add "Explain" button to SQL Runner
- [ ] Build plan tree visualization (D3.js or simple tree)
- [ ] Display issues and recommendations
- [ ] Add copy buttons for optimization SQL

**Day 5: Testing**
- [ ] Test with various query types (SELECT, JOIN, subqueries)
- [ ] Verify issue detection accuracy
- [ ] Test both MySQL and PostgreSQL
- [ ] Integration testing with existing features

### Week 2: Slow Query Analysis

**Day 1-2: Backend**
- [ ] Create models (SlowQuery, SlowQueryAnalysis)
- [ ] Implement PostgreSQL pg_stat_statements integration
- [ ] Implement MySQL performance_schema queries
- [ ] Add slow log parsing (optional)
- [ ] Create SlowQueryController endpoints

**Day 3-4: Frontend**
- [ ] Create SlowQueryAnalysisTab component
- [ ] Build slow query table with sorting
- [ ] Add query detail modal
- [ ] Display optimization suggestions
- [ ] Add time range and threshold filters

**Day 5: Integration**
- [ ] Connect slow query analysis with EXPLAIN plan service
- [ ] Generate comprehensive recommendations
- [ ] Add AI-powered optimization suggestions
- [ ] Test end-to-end workflow

### Week 3: Polish & Documentation

**Day 1-2: AI Integration**
- [ ] Add GPT-4 query optimization suggestions
- [ ] Implement before/after comparison
- [ ] Add performance prediction

**Day 3: UI Polish**
- [ ] Add loading states and error handling
- [ ] Improve visualizations
- [ ] Add export functionality (PDF reports)

**Day 4-5: Documentation & Testing**
- [ ] User guide for performance features
- [ ] API documentation
- [ ] Comprehensive testing
- [ ] Performance benchmarks

---

## Success Metrics

**EXPLAIN Plan Analysis:**
- [ ] Successfully parse EXPLAIN output for MySQL and PostgreSQL
- [ ] Detect 90%+ of common performance issues
- [ ] Generate actionable optimization SQL
- [ ] Visualize plan tree clearly

**Slow Query Analysis:**
- [ ] Identify top 10 slowest queries in <2 seconds
- [ ] Provide optimization suggestions for 80%+ of slow queries
- [ ] Track performance improvements over time
- [ ] Reduce avg query time by 30-70% when recommendations applied

---

## Next Steps

1. Review and approve this plan
2. Set up development environment
3. Start with EXPLAIN plan backend implementation
4. Iterate based on testing feedback
5. Deploy and gather user feedback

Ready to start implementation?
