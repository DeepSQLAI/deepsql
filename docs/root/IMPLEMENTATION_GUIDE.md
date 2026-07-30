# Key Columns Analysis - Remaining Implementation Guide

## ✅ COMPLETED SO FAR

1. ✅ JSQLParser dependency added
2. ✅ Database migrations (V13, V14, V15) created
3. ✅ JPA entities created (KeyColumnAnalysis, ColumnUsagePattern, ColumnAntiPattern)
4. ✅ Repository interfaces created
5. ✅ EnhancedSqlParserService created - **READY TO USE**
6. ✅ Support DTOs created (ColumnUsageDetail, ColumnUsageExtraction)

## 🚀 QUICK PATH TO GET IT WORKING

### Option A: Minimal Working Version (Fastest - 1 hour)

Create a simplified KeyColumnAnalysisService that:
- Only analyzes SlowQueryHistory (skip lineage for now)
- Uses simple scoring algorithm
- Detects one anti-pattern (unindexed WHERE columns)
- No scheduled jobs yet (manual trigger only)

Then:
- Add one endpoint to BrainController
- Create minimal frontend display
- Test with existing slow query data

### Option B: Full Implementation (2-3 hours)

Complete everything in the original plan

## 📝 NEXT FILES NEEDED

### 1. KeyColumnAnalysisService.java (Core Service)

**Pseudo-code structure:**
```
class KeyColumnAnalysisService {
    // Dependencies: inject all repositories, parser service, schema scanner

    @Value("${brain.key-columns.weight.join:3}") int joinWeight;
    @Value("${brain.key-columns.weight.where:2}") int whereWeight;
    // ... other weights

    public KeyColumnAnalysisResult analyzeKeyColumns(connectionId) {
        1. Get schema metadata
        2. Create aggregator map: Map<"table.column", UsageAggregator>
        3. For each slow query:
           - Parse SQL with EnhancedSqlParserService
           - For each column found: increment counts in aggregator
        4. For each aggregator entry:
           - Calculate importance score
           - Get cardinality from ColumnProfile
           - Create KeyColumnAnalysis entity
        5. Detect anti-patterns
        6. Save all entities
        7. Return result
    }

    private double calculateScore(agg) {
        weightedSum = join*3 + where*2 + groupBy*2 + orderBy*1
        return min(100, (weightedSum / 50.0) * 100)
    }

    private List<AntiPattern> detectAntiPatterns(analyses) {
        for each analysis:
            if (whereCount >= 5 && !isIndexed):
                create UNINDEXED_FILTER pattern
            if (groupByCount >= 3 && selectivity < 0.01):
                create LOW_CARDINALITY_GROUP_BY pattern
            // ... more rules
        return patterns
    }

    private boolean isIndexed(analysis) {
        // Query schema metadata to check if column has index
    }
}
```

### 2. Response DTOs (Simple POJOs)

Create in `backend/src/main/java/com/dbaagent/dto/`:

**KeyColumnAnalysisResult.java:**
```java
@Data @Builder
class KeyColumnAnalysisResult {
    List<KeyColumnScore> topColumns;
    Integer totalColumnsAnalyzed;
    Integer antiPatternsDetected;
    LocalDateTime analyzedAt;
}
```

**KeyColumnScore.java:**
```java
@Data @Builder
class KeyColumnScore {
    String tableName, columnName, dataType;
    Double importanceScore;
    UsageBreakdown usageBreakdown;
    Long distinctCount, totalRows;
    Double selectivity;
    Boolean isIndexed;
    List<String> indexNames;
    Boolean hasAntiPatterns;
    List<AntiPatternSummary> antiPatterns;
}
```

**UsageBreakdown.java:**
```java
@Data @Builder
class UsageBreakdown {
    Integer joinCount, whereCount, groupByCount, orderByCount, totalUsage;
    Integer slowQueryUsage, lineageUsage, performanceHistoryUsage;
}
```

**AntiPatternSummary.java:**
```java
@Data @Builder
class AntiPatternSummary {
    String id, patternType, severity, title, description, recommendation;
    Integer affectedQueriesCount;
    String status;
}
```

### 3. BrainController Extensions

Add to existing controller:

```java
@Autowired
private KeyColumnAnalysisService keyColumnAnalysisService;

@GetMapping("/key-columns/{connectionId}")
public ResponseEntity<KeyColumnAnalysisResult> getKeyColumns(
    @PathVariable String connectionId,
    @RequestParam(defaultValue = "50") Integer limit
) {
    try {
        // Fetch existing analysis from database
        List<KeyColumnAnalysis> analyses = keyColumnAnalysisRepository
            .findTopKeyColumns(connectionId, BigDecimal.ZERO, PageRequest.of(0, limit));

        // Convert to result DTO
        KeyColumnAnalysisResult result = convertToResult(analyses);
        return ResponseEntity.ok(result);
    } catch (Exception e) {
        log.error("Error fetching key columns", e);
        return ResponseEntity.status(500).build();
    }
}

@PostMapping("/key-columns/analyze/{connectionId}")
public ResponseEntity<KeyColumnAnalysisResult> analyzeKeyColumns(
    @PathVariable String connectionId
) {
    try {
        KeyColumnAnalysisResult result = keyColumnAnalysisService.analyzeKeyColumns(connectionId);
        return ResponseEntity.ok(result);
    } catch (Exception e) {
        log.error("Error analyzing key columns", e);
        return ResponseEntity.status(500).build();
    }
}
```

### 4. Configuration Properties

Add to `application.properties`:

```properties
# Key column analysis configuration
brain.key-columns.weight.join=3
brain.key-columns.weight.where=2
brain.key-columns.weight.group-by=2
brain.key-columns.weight.order-by=1
brain.key-columns.lookback-days=90
```

### 5. Frontend - KeyColumnsPanel.js

Basic structure:

```jsx
export function KeyColumnsPanel({ connectionId }) {
    const [data, setData] = useState(null);
    const [analyzing, setAnalyzing] = useState(false);

    const analyze = async () => {
        setAnalyzing(true);
        const response = await fetch(`/api/brain/key-columns/analyze/${connectionId}`, {
            method: 'POST'
        });
        const result = await response.json();
        setData(result);
        setAnalyzing(false);
    };

    return (
        <div>
            <button onClick={analyze} disabled={analyzing}>
                {analyzing ? 'Analyzing...' : 'Analyze Key Columns'}
            </button>

            {data && (
                <table>
                    <thead>
                        <tr>
                            <th>Table</th>
                            <th>Column</th>
                            <th>Score</th>
                            <th>JOIN</th>
                            <th>WHERE</th>
                            <th>GROUP BY</th>
                            <th>ORDER BY</th>
                            <th>Issues</th>
                        </tr>
                    </thead>
                    <tbody>
                        {data.topColumns.map(col => (
                            <tr key={`${col.tableName}.${col.columnName}`}>
                                <td>{col.tableName}</td>
                                <td>{col.columnName}</td>
                                <td>{col.importanceScore.toFixed(1)}</td>
                                <td>{col.usageBreakdown.joinCount}</td>
                                <td>{col.usageBreakdown.whereCount}</td>
                                <td>{col.usageBreakdown.groupByCount}</td>
                                <td>{col.usageBreakdown.orderByCount}</td>
                                <td>
                                    {col.hasAntiPatterns && (
                                        <span className="warning">
                                            {col.antiPatterns.length} issues
                                        </span>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
}
```

## 🎯 RECOMMENDED NEXT STEPS

1. **Test what we have so far:**
   ```bash
   cd backend
   mvn clean install -DskipTests
   # Check for compilation errors
   ```

2. **If successful, create KeyColumnAnalysisService** (the main piece)
   - Start with minimal version analyzing only SlowQueryHistory
   - Add scoring calculation
   - Add one anti-pattern detection rule

3. **Add endpoint to BrainController**

4. **Test backend:**
   ```bash
   mvn spring-boot:run
   # In another terminal:
   curl -X POST http://localhost:8080/api/brain/key-columns/analyze/{your-connection-id}
   ```

5. **If backend works, add minimal frontend**

6. **Test end-to-end**

7. **Iterate and enhance**

## 🐛 POTENTIAL ISSUES & FIXES

**Issue 1:** JSQLParser can't parse complex queries
- **Fix:** Fallback regex parsing is already implemented

**Issue 2:** Column not found in schema metadata
- **Fix:** Still create analysis but mark tableName as null

**Issue 3:** No slow queries exist yet
- **Fix:** Run slow query analysis first, or analyze QueryLineage instead

**Issue 4:** Frontend CORS errors
- **Fix:** Check CORS configuration in SecurityConfig

## 📚 FILES TO CREATE SUMMARY

Still need:
1. ✅ KeyColumnAnalysisService.java (~400 lines) - CORE FILE
2. ✅ 4 Response DTOs (~50 lines each = 200 lines total)
3. ✅ BrainController additions (~50 lines)
4. ✅ KeyColumnsPanel.js (~300 lines)
5. ✅ useKeyColumns.js hook (~100 lines)
6. ✅ API client additions (~20 lines)

**Total remaining: ~1,070 lines of code**

**Estimated time:** 2-3 hours for full implementation

Would you like me to:
A) Create the remaining backend files now (KeyColumnAnalysisService + DTOs + Controller changes)
B) Provide templates/stubs you can fill in
C) Focus on getting a minimal working version first
