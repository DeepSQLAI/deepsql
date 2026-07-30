# Key Columns Analysis - Implementation Status

## ✅ COMPLETED

### Phase 1: Backend Foundation
1. ✅ Added JSQLParser 5.2 dependency to pom.xml
2. ✅ Created database migrations:
   - V13__create_key_column_analysis.sql
   - V14__create_column_usage_pattern.sql
   - V15__create_column_anti_pattern.sql
3. ✅ Created JPA entities:
   - KeyColumnAnalysis.java
   - ColumnUsagePattern.java
   - ColumnAntiPattern.java
4. ✅ Created repository interfaces:
   - KeyColumnAnalysisRepository.java
   - ColumnUsagePatternRepository.java
   - ColumnAntiPatternRepository.java

## 🚧 IN PROGRESS / TODO

### Phase 2: Services (Critical)

#### 1. EnhancedSqlParserService.java
**Location:** `backend/src/main/java/com/dbaagent/service/EnhancedSqlParserService.java`

**Purpose:** Parse SQL to extract column usage by type (JOIN, WHERE, GROUP BY, ORDER BY)

**Key Methods Needed:**
```java
public ColumnUsageExtraction extractColumnUsage(String sql)
    // Returns: joinColumns, whereColumns, groupByColumns, orderByColumns

private List<ColumnUsageDetail> extractJoinColumns(Statement stmt)
    // Parse JOIN...ON clauses

private List<ColumnUsageDetail> extractWhereColumns(Expression where)
    // Parse WHERE predicates

private List<ColumnUsageDetail> extractGroupByColumns(GroupByElement groupBy)
    // Parse GROUP BY columns

private List<ColumnUsageDetail> extractOrderByColumns(List<OrderByElement> orderBy)
    // Parse ORDER BY columns
```

**Implementation Notes:**
- Use JSQLParser's CCJSqlParserUtil.parse() to parse SQL
- Handle SelectStatement, InsertStatement, UpdateStatement, DeleteStatement
- Extract table aliases (AS mappings)
- Resolve qualified vs unqualified column references
- Fall back to regex parsing if JSQLParser fails

#### 2. KeyColumnAnalysisService.java
**Location:** `backend/src/main/java/com/dbaagent/service/KeyColumnAnalysisService.java`

**Purpose:** Orchestrate analysis, calculate scores, detect anti-patterns

**Key Methods Needed:**
```java
public KeyColumnAnalysisResult analyzeKeyColumns(String connectionId)
    // Main analysis method

private void parseSlowQueries(String connectionId, LocalDateTime since, Map<String, ColumnUsageAggregator> aggregators)
    // Parse slow query history

private void parseQueryLineage(String connectionId, LocalDateTime since, Map<String, ColumnUsageAggregator> aggregators)
    // Parse query lineage

private double calculateScore(ColumnUsageAggregator agg)
    // Score = (joinCount * 3 + whereCount * 2 + groupByCount * 2 + orderByCount * 1) / 50 * 100

private List<ColumnAntiPattern> detectAntiPatterns(String connectionId, List<KeyColumnAnalysis> analyses)
    // Apply anti-pattern rules

private boolean isIndexed(KeyColumnAnalysis analysis)
    // Check if column has an index
```

**Configuration Properties:**
```properties
brain.key-columns.weight.join=3
brain.key-columns.weight.where=2
brain.key-columns.weight.group-by=2
brain.key-columns.weight.order-by=1
brain.key-columns.lookback-days=90
```

### Phase 3: REST API

#### 3. BrainController.java Extensions
**Location:** `backend/src/main/java/com/dbaagent/controller/BrainController.java`

**New Endpoints:**
```java
@GetMapping("/key-columns/{connectionId}")
public ResponseEntity<KeyColumnAnalysisResult> getKeyColumns(
    @PathVariable String connectionId,
    @RequestParam(defaultValue = "50") Integer limit,
    @RequestParam(required = false) String tableName,
    @RequestParam(defaultValue = "false") Boolean antiPatternsOnly
)

@PostMapping("/key-columns/analyze/{connectionId}")
public ResponseEntity<KeyColumnAnalysisResult> analyzeKeyColumns(
    @PathVariable String connectionId
)

@PostMapping("/key-columns/anti-pattern/{patternId}/acknowledge")
public ResponseEntity<ColumnAntiPattern> acknowledgeAntiPattern(
    @PathVariable String patternId
)
```

#### 4. Response DTOs
**Location:** `backend/src/main/java/com/dbaagent/dto/`

**Files Needed:**
- KeyColumnAnalysisResult.java
- KeyColumnScore.java
- UsageBreakdown.java
- AntiPatternSummary.java
- AnalysisMetadata.java

### Phase 4: Frontend

#### 5. KeyColumnsPanel Component
**Location:** `src/components/tabs/Brain/KeyColumnsPanel.js`

**Features:**
- Table of key columns with importance scores
- Usage breakdown badges (JOIN/WHERE/GROUP BY/ORDER BY)
- Cardinality metrics (distinct count, selectivity)
- Anti-pattern warnings with recommendations
- Filters (table name, anti-patterns only, min score)
- Sort options (by score, usage, anti-patterns)
- Expandable rows for details

#### 6. useKeyColumns Hook
**Location:** `src/components/tabs/Brain/hooks/useKeyColumns.js`

**Methods:**
- fetchKeyColumns(filters)
- analyzeKeyColumns()
- acknowledgeAntiPattern(patternId)

#### 7. API Client Integration
**Location:** `src/lib/api/client.js`

**Add to brainAPI:**
```javascript
getKeyColumns: (connectionId, filters) =>
    client.get(`/brain/key-columns/${connectionId}`, { params: filters }),

analyzeKeyColumns: (connectionId) =>
    client.post(`/brain/key-columns/analyze/${connectionId}`),

acknowledgeAntiPattern: (patternId) =>
    client.post(`/brain/key-columns/anti-pattern/${patternId}/acknowledge`)
```

## 🎯 NEXT STEPS

1. **Implement EnhancedSqlParserService** (highest priority)
   - This is the foundation for everything else
   - Use JSQLParser library that's already added
   - Create supporting classes: ColumnUsageExtraction, ColumnUsageDetail

2. **Implement KeyColumnAnalysisService**
   - Aggregate usage from SlowQueryHistory, QueryLineage, QueryPerformanceHistory
   - Calculate importance scores
   - Detect anti-patterns (unindexed filters, low-cardinality GROUP BY, etc.)

3. **Create Response DTOs**
   - Simple POJOs with @Data, @Builder annotations

4. **Add BrainController endpoints**
   - Wire up the service
   - Add error handling

5. **Add configuration properties**
   - Edit application.properties with weights and settings

6. **Create frontend components**
   - KeyColumnsPanel with sub-components
   - useKeyColumns hook
   - Update API client

7. **Integrate into Brain tab**
   - Add navigation item
   - Export from index.js

8. **Test end-to-end**
   - Rebuild backend: `cd backend && mvn clean install`
   - Rebuild frontend: `npm run build`
   - Restart services
   - Test with sample data

## 📝 QUICK START COMMANDS

```bash
# Rebuild backend
cd backend
mvn clean install -DskipTests

# Restart backend
mvn spring-boot:run

# Rebuild frontend
cd ..
npm install
npm run build

# Start frontend
npm run dev
```

## 🔍 VERIFICATION

Once implemented, verify:
1. Database tables created: `SHOW TABLES LIKE 'key_column%'`
2. Analyze endpoint works: `POST /brain/key-columns/analyze/{connectionId}`
3. Results stored: `SELECT * FROM key_column_analysis ORDER BY importance_score DESC LIMIT 10`
4. Frontend displays: Navigate to Brain → Key Columns
5. Anti-patterns detected: Check for warnings on high-usage unindexed columns

## 📚 REFERENCE

- Plan file: `/Users/geekypunk/.claude/plans/giggly-hopping-hopper.md`
- JSQLParser docs: https://github.com/JSQLParser/JSQLParser
- Existing slow query service: `backend/src/main/java/com/dbaagent/service/SlowQueryService.java`
- Existing Brain service: `backend/src/main/java/com/dbaagent/service/BrainService.java`
