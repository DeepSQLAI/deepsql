# Brain 2.0 Implementation Plan

> **Revision 2** - Updated with Codex corrections for API shapes, metric keys, and missing UX

## Executive Summary

This document provides a comprehensive implementation plan for improving the Brain 2.0 ML-based database optimization features, based on:
- Analysis of the current codebase gaps identified by Codex
- Research from CMU's OtterTune (2017-2021) and optd projects
- Best practices from production database tuning systems

**Key Finding:** The current implementation uses "heuristics + LLM reasoning" but documentation claims "factor analysis, k-means, GP regression, Lasso." This creates a trust gap with DBAs. The plan addresses both functional gaps and documentation accuracy.

---

## Research Summary

### OtterTune Key Insights (2017 SIGMOD + 2021 PVLDB)

| Component | OtterTune Approach | Current DBA-Agent Implementation |
|-----------|-------------------|----------------------------------|
| **Metric Pruning** | Factor Analysis reduces 131 MySQL metrics by 93% | None - uses all metrics |
| **Workload Mapping** | K-means clustering on factor coefficients | Simple threshold-based classification |
| **Knob Identification** | Lasso regression with polynomial features | Heuristic ranking by category |
| **Configuration Recommendation** | Gaussian Process regression with exploration/exploitation | LLM prompting (valid but different) |
| **Metric Handling** | Uses binned decile values | Averages cumulative counters (bug) |
| **Knowledge Transfer** | Euclidean distance to similar workloads | Fingerprint vector similarity |

**Critical 2021 Paper Insights:**
1. **Storage architecture matters** - Non-local storage (SAN, cloud block stores) introduces latency variance that invalidates controlled-environment tuning
2. **Measurement hygiene** - Previous studies "vague about how much was truly automated"
3. **Configuration bounds** - How knob ranges are selected dramatically impacts results
4. **45% improvement over enterprise configs** - But only with proper measurement methodology

### optd Key Insights

| Concept | optd Approach | Relevance to DBA-Agent |
|---------|--------------|------------------------|
| **Plan Memoization** | Persistent storage of plans + statistics for reuse | Our `PlanPattern` entity already does this |
| **Adaptive Optimization** | Captures runtime info to guide subsequent searches | Our `PlanExecution` tracks actual vs estimated |
| **Cardinality Estimation** | TDigest, HyperLogLog for scalable estimation | We use database-native stats (simpler) |
| **Rule DSL** | Turing-complete transformation rules | Not applicable - we analyze, not rewrite plans |

---

## Priority Levels

| Priority | Definition | SLA |
|----------|------------|-----|
| **P0** | Broken functionality / crashes / data corruption | Must fix before any release |
| **P1** | Incorrect results / misleading outputs | Fix in next sprint |
| **P2** | Missing features / poor UX | Plan for roadmap |
| **P3** | Documentation / nice-to-have | As time permits |

---

## Codex Review Corrections (v2)

The following corrections were identified in Codex's second review:

| Original Plan Item | Issue | Correction | Status |
|--------------------|-------|------------|--------|
| **Phase 1.1** `collectCurrentMetrics()` | Keys `latency_p50`, `latency_p99`, `throughput_qps` don't exist in `WorkloadMetricsCollectorService` | Use actual collected keys: `cache_hit_ratio`, `read_write_ratio`, `xact_commit`, `pg_stat_avg_mean_exec_time` (PG) or `innodb_buffer_hit_ratio`, `com_select` (MySQL) | ✅ Fixed in §1.1 |
| **Phase 2.1** Rate metrics | MySQL counters use `Com_select` | Actually stored as lowercase: `com_select`, `com_insert`, etc. | ✅ Noted in §2.1 |
| **Phase 3.1** Pattern feedback API | Plan uses JSON body `{ wasSuccessful }` | Backend uses `@RequestParam`: `POST /patterns/{id}/feedback?wasSuccessful=true` | ✅ Fixed in §3.1 |
| **Phase 3.2** ML health widget | Plan expects booleans like `workloadProfileExists` | Endpoint returns nested objects: `workloadProfile`, `configTuning`, `queryIntelligence` | ✅ Fixed in §3.2 |
| **Phase 1.3** rollbackSql field | Adding to entity requires Flyway migration | Return in response DTO instead, or add migration V47 | ⚠️ See note below |
| **Missing P0 UX** | Experiments get stuck in RUNNING | Add "Complete Experiment" button to `ConfigTuningPanel.js` | ✅ Added as §1.4 |

**Note on rollbackSql migration:**
If adding `rollbackSql` to the `ConfigurationRecommendation` entity, create a Flyway migration:
```sql
-- V47__add_rollback_sql_column.sql
ALTER TABLE configuration_recommendation ADD COLUMN IF NOT EXISTS rollback_sql TEXT;
```
Alternatively, compute `rollbackSql` at runtime and return it in the DTO without persisting.

### Actual Metric Keys (from WorkloadMetricsCollectorService)

**PostgreSQL metrics:**
```
cache_hit_ratio, read_write_ratio, numbackends, xact_commit, xact_rollback,
blks_read, blks_hit, tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted,
temp_files, deadlocks, conn_total, conn_active, conn_idle, conn_waiting,
total_seq_scan, total_idx_scan, total_tup_ins, total_tup_upd, total_tup_del,
pg_stat_total_calls, pg_stat_total_exec_time, pg_stat_avg_mean_exec_time
```

**MySQL metrics (lowercase):**
```
innodb_buffer_hit_ratio, read_write_ratio, com_select, com_insert, com_update, com_delete,
slow_queries, threads_connected, threads_running, conn_total, conn_active,
innodb_buffer_pool_read_requests, innodb_buffer_pool_reads,
innodb_rows_read, innodb_rows_inserted, innodb_rows_updated, innodb_rows_deleted
```

### ML Overview Endpoint Response Shape

`GET /api/brain/ml-overview/{connectionId}` returns:
```json
{
  "workloadProfile": {
    "type": "OLTP",
    "subtype": "High-Cache OLTP",
    "confidence": 85.5,
    "lastUpdated": "2024-01-15T10:30:00"
  },
  "configTuning": {
    "knobsRanked": 14,
    "experimentSuccessRate": 66.7
  },
  "queryIntelligence": {
    "statisticsCount": 25,
    "calibrationStatus": {...},
    "patternStats": {...}
  }
}
```

---

## Definition of Done (per Phase)

### Phase 1 DoD
- [ ] `collectCurrentMetrics()` returns non-zero values from actual snapshots
- [ ] Experiment baseline metrics show real values in UI
- [ ] ConfigTuningPanel correctly displays `knobChanges` map data
- [ ] "Complete Experiment" button visible for RUNNING experiments
- [ ] "Cancel Experiment" button for stuck experiments
- [ ] Elapsed time shown for RUNNING experiments
- [ ] Measurement hygiene warnings displayed (baseline data, cloud storage, workload shift)
- [ ] Unit tests pass for metric collection and experiment lifecycle
- [ ] Manual test: Create experiment → verify baseline shows real P50/cache values

### Phase 2 DoD
- [ ] Workload classification uses rate-based metrics (deltas between snapshots)
- [ ] Classification reasoning mentions "X operations/second" not cumulative counts
- [ ] PostgreSQL cardinality recommendations include ANALYZE, statistics_target, extended stats
- [ ] Unit tests for delta computation with counter resets

### Phase 3 DoD
- [ ] Pattern feedback buttons visible in QueryIntelligencePanel
- [ ] Clicking thumbs up/down updates pattern effectiveness score
- [ ] ML Health widget shows 4-step readiness checklist
- [ ] "Do the next thing" buttons link to correct Brain tabs

---

## Phase 1: Critical Fixes (P0)

### 1.1 Fix `collectCurrentMetrics()` Placeholder

**File:** `backend/src/main/java/com/dbaagent/service/brain/config/ConfigTuningService.java:477-485`

**Current Bug:**
```java
private Map<String, Double> collectCurrentMetrics(String connectionId) {
    Map<String, Double> metrics = new HashMap<>();
    // Placeholder - in production, would collect actual metrics
    metrics.put("latency_p50", 0.0);
    metrics.put("latency_p99", 0.0);
    metrics.put("throughput", 0.0);
    metrics.put("cache_hit_ratio", 0.0);
    return metrics;
}
```

**Impact:** Experiments always show 0% improvement because baseline == new metrics.

**Fix Implementation (CORRECTED with actual metric keys):**

```java
private Map<String, Double> collectCurrentMetrics(String connectionId) {
    Map<String, Double> metrics = new HashMap<>();

    try {
        ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
        String dbType = providerRegistry.getCanonicalName(connection.getDbType());

        // Get recent workload metrics snapshots
        List<WorkloadMetricsSnapshot> snapshots = snapshotRepository
            .findByConnectionIdOrderByCollectedAtDesc(connectionId, PageRequest.of(0, 5));

        if (snapshots.isEmpty()) {
            log.warn("No metrics snapshots for {}, triggering collection", connectionId);
            WorkloadMetricsSnapshot fresh = metricsCollectorService.collectMetrics(connectionId);
            snapshots = List.of(fresh);
        }

        // Define keys based on database type (CORRECTED: use actual keys from collector)
        List<String> gaugeKeys;
        List<String> counterKeys;

        if ("postgres".equals(dbType)) {
            gaugeKeys = List.of("cache_hit_ratio", "read_write_ratio", "conn_active", "conn_total");
            counterKeys = List.of("xact_commit", "xact_rollback", "total_seq_scan", "total_idx_scan",
                                   "pg_stat_total_calls", "pg_stat_total_exec_time");
        } else { // mysql - keys are LOWERCASE
            gaugeKeys = List.of("innodb_buffer_hit_ratio", "read_write_ratio", "conn_active", "threads_running");
            counterKeys = List.of("com_select", "com_insert", "com_update", "com_delete",
                                   "slow_queries", "innodb_rows_read");
        }

        // Collect gauge metrics (can average directly)
        Map<String, List<Double>> metricHistory = new HashMap<>();
        for (WorkloadMetricsSnapshot snapshot : snapshots) {
            Map<String, Object> raw = snapshot.getRawMetrics();
            if (raw == null) continue;

            for (String key : gaugeKeys) {
                Object value = raw.get(key);
                if (value instanceof Number) {
                    metricHistory.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(((Number) value).doubleValue());
                }
            }
        }

        // Compute gauge averages
        for (Map.Entry<String, List<Double>> entry : metricHistory.entrySet()) {
            double avg = entry.getValue().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            metrics.put(entry.getKey(), avg);
        }

        // Compute counter rates (delta between first and last snapshot)
        if (snapshots.size() >= 2) {
            WorkloadMetricsSnapshot newest = snapshots.get(0);
            WorkloadMetricsSnapshot oldest = snapshots.get(snapshots.size() - 1);

            long intervalSeconds = Math.max(1,
                java.time.Duration.between(oldest.getCollectedAt(), newest.getCollectedAt()).getSeconds());

            Map<String, Object> newestRaw = newest.getRawMetrics();
            Map<String, Object> oldestRaw = oldest.getRawMetrics();

            if (newestRaw != null && oldestRaw != null) {
                for (String key : counterKeys) {
                    Object newVal = newestRaw.get(key);
                    Object oldVal = oldestRaw.get(key);
                    if (newVal instanceof Number && oldVal instanceof Number) {
                        double delta = ((Number) newVal).doubleValue() - ((Number) oldVal).doubleValue();
                        if (delta >= 0) {  // Handle counter resets
                            metrics.put(key + "_rate", delta / intervalSeconds);
                        }
                    }
                }

                // Derive throughput (queries per second)
                if ("postgres".equals(dbType)) {
                    Double callsRate = metrics.get("pg_stat_total_calls_rate");
                    if (callsRate != null) {
                        metrics.put("throughput_qps", callsRate);
                    }
                } else {
                    // MySQL: sum of com_* rates
                    double qps = metrics.getOrDefault("com_select_rate", 0.0) +
                                 metrics.getOrDefault("com_insert_rate", 0.0) +
                                 metrics.getOrDefault("com_update_rate", 0.0) +
                                 metrics.getOrDefault("com_delete_rate", 0.0);
                    metrics.put("throughput_qps", qps);
                }
            }
        }

        // Derive latency from pg_stat_statements or slow query history
        enrichLatencyMetrics(connectionId, dbType, snapshots, metrics);

        log.info("Collected metrics for {}: {}", connectionId, metrics);

    } catch (Exception e) {
        log.error("Failed to collect metrics for {}: {}", connectionId, e.getMessage());
    }

    return metrics;
}

private void enrichLatencyMetrics(String connectionId, String dbType,
                                   List<WorkloadMetricsSnapshot> snapshots,
                                   Map<String, Double> metrics) {
    // Try pg_stat_statements first (if available)
    if ("postgres".equals(dbType) && !snapshots.isEmpty()) {
        Map<String, Object> raw = snapshots.get(0).getRawMetrics();
        if (raw != null) {
            Object avgMean = raw.get("pg_stat_avg_mean_exec_time");
            Object maxExec = raw.get("pg_stat_max_exec_time");
            if (avgMean instanceof Number) {
                metrics.put("latency_avg_ms", ((Number) avgMean).doubleValue());
            }
            if (maxExec instanceof Number) {
                metrics.put("latency_max_ms", ((Number) maxExec).doubleValue());
            }
        }
    }

    // Fallback: derive P50/P99 from slow query history
    if (!metrics.containsKey("latency_avg_ms")) {
        try {
            var histories = slowQueryHistoryService.getRecentHistorySummaries(connectionId);
            if (!histories.isEmpty()) {
                var latestOpt = slowQueryHistoryService.getById(histories.get(0).getId());
                if (latestOpt.isPresent()) {
                    var analysis = slowQueryHistoryService.getAnalysisData(latestOpt.get());
                    if (analysis != null && analysis.getTopSlowQueries() != null) {
                        List<Double> durations = analysis.getTopSlowQueries().stream()
                            .map(q -> q.getAvgDurationMs())
                            .filter(Objects::nonNull)
                            .sorted()
                            .toList();

                        if (!durations.isEmpty()) {
                            int p50Idx = (int) (durations.size() * 0.5);
                            int p99Idx = Math.min((int) (durations.size() * 0.99), durations.size() - 1);
                            metrics.put("latency_p50_ms", durations.get(p50Idx));
                            metrics.put("latency_p99_ms", durations.get(p99Idx));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not enrich latency from slow query history: {}", e.getMessage());
        }
    }
}
```

**Dependencies:**
- Inject `WorkloadMetricsSnapshotRepository` (already available via `snapshotRepository` in many services)
- Inject `WorkloadMetricsCollectorService` (already injected as `metricsCollectorService`)
- Inject `SlowQueryHistoryService` (instead of repository, use the service for safe data access)

---

### 1.2 Fix TuningExperiment Field Mapping in UI

**File:** `src/components/tabs/Brain/ConfigTuningPanel.js:599-658`

**Current Bug:** UI expects fields that don't exist on `TuningExperiment` entity:
- `exp.knobName` → Entity has `knobChanges` (Map)
- `exp.originalValue` → Entity has `baselineLatencyP50`, etc.
- `exp.result.improvement` → Entity has `overallImprovementPercent`

**Fix Implementation:**

```javascript
// Replace lines 599-658 with proper field access
{experiments.map((exp, idx) => {
  // Extract first knob from knobChanges map
  const knobEntries = exp.knobChanges ? Object.entries(exp.knobChanges) : [];
  const firstKnob = knobEntries[0] || ['Unknown', { current: '-', recommended: '-' }];
  const [knobName, knobValues] = firstKnob;

  return (
    <div key={idx} style={{
      padding: '16px',
      background: 'var(--color-light-1)',
      borderRadius: '8px',
      border: '1px solid var(--color-light-2)'
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <ExperimentStatusIcon status={exp.status} />
          <span style={{ fontWeight: 600, fontFamily: 'monospace' }}>{knobName}</span>
          {knobEntries.length > 1 && (
            <span style={{ fontSize: '11px', color: 'var(--color-light-6)' }}>
              +{knobEntries.length - 1} more
            </span>
          )}
        </div>
        <span style={{
          padding: '4px 8px',
          borderRadius: '4px',
          fontSize: '11px',
          fontWeight: 500,
          background: getStatusColor(exp.status).bg,
          color: getStatusColor(exp.status).text
        }}>
          {exp.status || 'PENDING'}
        </span>
      </div>

      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(4, 1fr)',
        gap: '12px',
        fontSize: '13px'
      }}>
        <div>
          <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Original</div>
          <div style={{ fontFamily: 'monospace' }}>{formatValue(knobValues?.current || knobValues?.old)}</div>
        </div>
        <div>
          <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Target</div>
          <div style={{ fontFamily: 'monospace', color: 'var(--color-dark-grey)' }}>
            {formatValue(knobValues?.recommended || knobValues?.new)}
          </div>
        </div>
        {exp.status === 'COMPLETED' && (
          <>
            <div>
              <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Latency Change</div>
              <div style={{
                fontWeight: 500,
                color: exp.latencyImprovementPercent > 0 ? 'var(--color-success)' : 'var(--color-danger)'
              }}>
                {exp.latencyImprovementPercent != null
                  ? `${exp.latencyImprovementPercent > 0 ? '-' : '+'}${Math.abs(exp.latencyImprovementPercent).toFixed(1)}%`
                  : '-'}
              </div>
            </div>
            <div>
              <div style={{ color: 'var(--color-light-6)', marginBottom: '2px' }}>Overall</div>
              <div style={{
                fontWeight: 500,
                color: exp.overallImprovementPercent > 0 ? 'var(--color-success)' : 'var(--color-danger)'
              }}>
                {exp.overallImprovementPercent != null
                  ? `${exp.overallImprovementPercent > 0 ? '+' : ''}${exp.overallImprovementPercent.toFixed(1)}%`
                  : '-'}
              </div>
            </div>
          </>
        )}
      </div>

      {/* Show baseline metrics when running */}
      {exp.status === 'RUNNING' && exp.baselineMetrics && (
        <div style={{ marginTop: '12px', padding: '8px', background: 'white', borderRadius: '4px' }}>
          <div style={{ fontSize: '12px', color: 'var(--color-light-6)', marginBottom: '4px' }}>Baseline Metrics</div>
          <div style={{ display: 'flex', gap: '16px', fontSize: '12px' }}>
            <span>P50: {exp.baselineLatencyP50?.toFixed(1)}ms</span>
            <span>P99: {exp.baselineLatencyP99?.toFixed(1)}ms</span>
            <span>Throughput: {exp.baselineThroughput?.toFixed(0)} qps</span>
          </div>
        </div>
      )}

      <div style={{ marginTop: '12px', fontSize: '12px', color: 'var(--color-light-6)' }}>
        Created: {exp.createdAt ? new Date(exp.createdAt).toLocaleString() : '-'}
        {exp.completedAt && ` | Completed: ${new Date(exp.completedAt).toLocaleString()}`}
      </div>
    </div>
  );
})}
```

---

### 1.3 Fix ConfigurationRecommendation Required Fields

**File:** `backend/src/main/java/com/dbaagent/model/ConfigurationRecommendation.java`

**Current Bug:** Entity has `@Column(nullable = false)` on `impactDescription` and `applySql`, but `ConfigTuningService.parseAIRecommendations()` doesn't set them.

**Option A: Make fields nullable (Quick Fix)**
```java
@Column(columnDefinition = "TEXT")  // Remove nullable = false
private String impactDescription;

@Column(columnDefinition = "TEXT")  // Remove nullable = false
private String applySql;
```

**Option B: Generate proper values (Recommended)**

Update `parseAIRecommendations()` to generate these fields:

```java
private List<ConfigurationRecommendation> parseAIRecommendations(String connectionId, String response) {
    List<ConfigurationRecommendation> recommendations = new ArrayList<>();
    ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
    String dbType = providerRegistry.getCanonicalName(connection.getDbType());

    String[] blocks = response.split("---");
    for (String block : blocks) {
        if (block.trim().isEmpty()) continue;

        try {
            String knob = extractValue(block, "KNOB:");
            String current = extractValue(block, "CURRENT:");
            String recommended = extractValue(block, "RECOMMENDED:");
            String priority = extractValue(block, "PRIORITY:");
            String improvement = extractValue(block, "IMPROVEMENT:");
            String reason = extractValue(block, "REASON:");
            String restart = extractValue(block, "RESTART:");

            if (knob != null && recommended != null) {
                // Generate applySql based on database type
                String applySql = generateApplySql(dbType, knob, recommended,
                    restart != null && restart.equalsIgnoreCase("YES"));

                // Generate impact description
                String impactDescription = generateImpactDescription(knob, current, recommended,
                    improvement, restart);

                // Generate rollback SQL
                String rollbackSql = generateRollbackSql(dbType, knob, current,
                    restart != null && restart.equalsIgnoreCase("YES"));

                recommendations.add(ConfigurationRecommendation.builder()
                    .connectionId(connectionId)
                    .parameterName(knob)
                    .currentValue(current)
                    .recommendedValue(recommended)
                    .priority(parsePriority(priority))
                    .reasoning(reason)
                    .estimatedImprovementPercent(parseImprovement(improvement))
                    .requiresRestart(restart != null && restart.equalsIgnoreCase("YES"))
                    .category("Brain 2.0 ML")
                    // NEW FIELDS
                    .impactDescription(impactDescription)
                    .applySql(applySql)
                    .rollbackSql(rollbackSql)  // Add this field to entity
                    .build());
            }
        } catch (Exception e) {
            log.debug("Could not parse recommendation block: {}", e.getMessage());
        }
    }

    return recommendations;
}

private String generateApplySql(String dbType, String knob, String value, boolean requiresRestart) {
    if ("postgres".equals(dbType)) {
        if (requiresRestart) {
            return String.format("""
                -- Requires PostgreSQL restart to take effect
                ALTER SYSTEM SET %s = '%s';

                -- To apply without restart (if supported):
                -- SELECT pg_reload_conf();

                -- Verify change:
                -- SHOW %s;
                """, knob, value, knob);
        } else {
            return String.format("""
                -- Can be applied without restart
                ALTER SYSTEM SET %s = '%s';
                SELECT pg_reload_conf();

                -- Verify change:
                SHOW %s;
                """, knob, value, knob);
        }
    } else if ("mysql".equals(dbType)) {
        if (requiresRestart) {
            return String.format("""
                -- Requires MySQL restart to take effect
                -- Add to my.cnf/my.ini:
                -- [mysqld]
                -- %s = %s

                -- Or use SET PERSIST (MySQL 8.0+):
                SET PERSIST %s = %s;

                -- Verify change:
                SHOW VARIABLES LIKE '%s';
                """, knob, value, knob, value, knob);
        } else {
            return String.format("""
                -- Can be applied without restart
                SET GLOBAL %s = %s;

                -- To persist across restarts (MySQL 8.0+):
                SET PERSIST %s = %s;

                -- Verify change:
                SHOW VARIABLES LIKE '%s';
                """, knob, value, knob, value, knob);
        }
    }
    return String.format("-- Configure %s = %s in your database configuration", knob, value);
}

private String generateRollbackSql(String dbType, String knob, String originalValue, boolean requiresRestart) {
    // Same structure as generateApplySql but with originalValue
    return generateApplySql(dbType, knob, originalValue, requiresRestart)
        .replace("-- Requires", "-- ROLLBACK: Requires")
        .replace("-- Can be applied", "-- ROLLBACK: Can be applied");
}

private String generateImpactDescription(String knob, String current, String recommended,
                                          String improvement, String restart) {
    StringBuilder desc = new StringBuilder();
    desc.append(String.format("Change %s from %s to %s.", knob, current, recommended));

    if (improvement != null && !improvement.isEmpty()) {
        desc.append(String.format(" Expected improvement: %s.", improvement));
    }

    if (restart != null && restart.equalsIgnoreCase("YES")) {
        desc.append(" WARNING: Requires database restart to take effect.");
    } else {
        desc.append(" Can be applied online without restart.");
    }

    return desc.toString();
}
```

**Also add to entity:**
```java
@Column(columnDefinition = "TEXT")
private String rollbackSql;  // SQL to revert the change
```

---

### 1.4 Add "Complete Experiment" Button (P0 UX)

**File:** `src/components/tabs/Brain/ConfigTuningPanel.js`

**Current Bug:** The backend supports completing experiments (`POST /api/brain/config/experiments/{experimentId}/complete`) but the UI doesn't expose a "Complete" action, so experiments get stuck in RUNNING state indefinitely.

**Impact:** Users cannot close the learning loop - baseline metrics are captured but final metrics are never compared, making experiments useless.

**Fix Implementation - Add Complete button to experiment cards:**

```javascript
// Add to the experiment card rendering (after the status badge section)
{exp.status === 'RUNNING' && (
  <div style={{
    marginTop: '12px',
    padding: '12px',
    background: 'var(--color-warning-soft)',
    borderRadius: '6px',
    border: '1px solid var(--color-warning)'
  }}>
    <div style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: '12px'
    }}>
      <div>
        <div style={{ fontSize: '13px', fontWeight: 500, marginBottom: '4px' }}>
          Experiment Running
        </div>
        <div style={{ fontSize: '12px', color: 'var(--color-light-6)' }}>
          Apply the configuration change, let workload stabilize, then complete the experiment to measure improvement.
        </div>
      </div>
      <button
        onClick={() => handleCompleteExperiment(exp.id)}
        disabled={completingExperiment === exp.id}
        style={{
          padding: '8px 16px',
          background: completingExperiment === exp.id ? 'var(--color-light-3)' : 'var(--color-primary)',
          color: 'white',
          border: 'none',
          borderRadius: '6px',
          fontSize: '12px',
          fontWeight: 500,
          cursor: completingExperiment === exp.id ? 'not-allowed' : 'pointer',
          whiteSpace: 'nowrap',
          display: 'flex',
          alignItems: 'center',
          gap: '6px'
        }}
      >
        {completingExperiment === exp.id ? (
          <>
            <Loader size={12} className="animate-spin" />
            Measuring...
          </>
        ) : (
          <>
            <CheckCircle size={12} />
            Complete Experiment
          </>
        )}
      </button>
    </div>

    {/* Show elapsed time since start */}
    {exp.startedAt && (
      <div style={{ marginTop: '8px', fontSize: '11px', color: 'var(--color-light-6)' }}>
        Started: {new Date(exp.startedAt).toLocaleString()}
        {' • '}
        Elapsed: {formatElapsedTime(new Date(exp.startedAt))}
      </div>
    )}
  </div>
)}
```

**Add handler and state:**

```javascript
// Add to component state
const [completingExperiment, setCompletingExperiment] = useState(null);

// Add handler function
const handleCompleteExperiment = async (experimentId) => {
  setCompletingExperiment(experimentId);
  setError(null);

  try {
    const result = await brainAPI.completeExperiment(experimentId, connectionId);

    // Show success feedback
    if (result.overallImprovementPercent !== undefined) {
      const improvement = result.overallImprovementPercent;
      const message = improvement > 0
        ? `Experiment completed! ${improvement.toFixed(1)}% improvement detected.`
        : improvement < 0
          ? `Experiment completed. Performance decreased by ${Math.abs(improvement).toFixed(1)}%. Consider rolling back.`
          : 'Experiment completed. No significant change detected.';

      // Could use toast notification here
      console.log(message);
    }

    // Refresh experiments list
    await fetchExperiments();

  } catch (err) {
    setError(`Failed to complete experiment: ${err.message || 'Unknown error'}`);
  } finally {
    setCompletingExperiment(null);
  }
};

// Add elapsed time formatter
const formatElapsedTime = (startDate) => {
  const now = new Date();
  const diffMs = now - startDate;
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffDays > 0) return `${diffDays}d ${diffHours % 24}h`;
  if (diffHours > 0) return `${diffHours}h ${diffMins % 60}m`;
  return `${diffMins}m`;
};
```

**Add API method to client.js:**

```javascript
// Add to brainAPI in src/lib/api/client.js
completeExperiment: (experimentId, connectionId) =>
  apiClient.post(`/brain/config/experiments/${experimentId}/complete`, null, {
    params: { connectionId }
  }),
```

**Also add Cancel Experiment button for stuck experiments:**

```javascript
{exp.status === 'RUNNING' && (
  <button
    onClick={() => handleCancelExperiment(exp.id)}
    style={{
      padding: '8px 12px',
      background: 'transparent',
      color: 'var(--color-danger)',
      border: '1px solid var(--color-danger)',
      borderRadius: '6px',
      fontSize: '11px',
      cursor: 'pointer',
      marginLeft: '8px'
    }}
  >
    Cancel
  </button>
)}

// Handler
const handleCancelExperiment = async (experimentId) => {
  if (!window.confirm('Cancel this experiment? Baseline metrics will be discarded.')) {
    return;
  }

  try {
    await brainAPI.cancelExperiment(experimentId, connectionId);
    await fetchExperiments();
  } catch (err) {
    setError(`Failed to cancel experiment: ${err.message}`);
  }
};

// API method (if backend supports it, otherwise add endpoint)
cancelExperiment: (experimentId, connectionId) =>
  apiClient.delete(`/brain/config/experiments/${experimentId}`, {
    params: { connectionId }
  }),
```

**Backend Verification:**
The endpoint already exists at `BrainController.java`:
```java
@PostMapping("/config/experiments/{experimentId}/complete")
public ResponseEntity<?> completeExperiment(
    @PathVariable Long experimentId,
    @RequestParam String connectionId) {
    // ...
}
```

**Testing Checklist:**
- [ ] RUNNING experiments show "Complete Experiment" button
- [ ] Clicking button shows loading state
- [ ] After completion, experiment status changes to COMPLETED
- [ ] Improvement percentage is displayed correctly (positive = green, negative = red)
- [ ] Elapsed time updates correctly
- [ ] Cancel button removes experiment from list
- [ ] Error states are handled gracefully

---

### 1.5 Measurement Hygiene Requirements

**Based on 2021 OtterTune Paper Insights:**
> "Previous studies were vague about how much was truly automated... Storage architecture matters... Configuration bounds selection dramatically impacts results."

**Implementation Guidelines:**

1. **Baseline Window Duration:**
   - Minimum 5 minutes of metric collection before baseline is considered valid
   - Add UI warning if experiment started with < 5 snapshots
   ```java
   // In startExperiment()
   if (snapshots.size() < 5) {
       log.warn("Starting experiment with only {} snapshots - baseline may be unreliable", snapshots.size());
       experiment.setBaselineWarning("Limited baseline data - collect more metrics for reliable comparison");
   }
   ```

2. **Workload Stability Check:**
   - Detect if workload changed significantly during experiment
   - Compare workload fingerprint before/after configuration change
   ```java
   // In completeExperiment()
   WorkloadProfile beforeProfile = getProfileAtTime(experiment.getStartedAt());
   WorkloadProfile afterProfile = getCurrentProfile(connectionId);

   if (!beforeProfile.getWorkloadType().equals(afterProfile.getWorkloadType())) {
       experiment.setWorkloadShiftWarning("Workload type changed during experiment - results may not be valid");
   }
   ```

3. **Storage Variance Warning:**
   - For cloud databases, warn about storage latency variability
   ```javascript
   // In ConfigTuningPanel.js experiment guidance
   {connection?.cloudProvider && (
     <div style={{
       padding: '8px',
       background: 'var(--color-warning-soft)',
       borderRadius: '4px',
       fontSize: '11px',
       marginTop: '8px'
     }}>
       <strong>Cloud Storage Note:</strong> Cloud block storage (EBS, Azure Disk, GCP PD)
       introduces latency variance. Run experiments during stable workload periods
       and collect multiple samples for reliable comparisons.
     </div>
   )}
   ```

4. **Configuration Bounds Display:**
   - Show valid range for each knob to prevent invalid configurations
   ```java
   // Add to KnobRanking entity or DTO
   private String minValue;      // e.g., "64MB" or "0.1"
   private String maxValue;      // e.g., "80% of RAM" or "0.9"
   private String safeDefault;   // Conservative default for unknown workloads
   ```

5. **Experiment Duration Recommendation:**
   - Suggest minimum observation period based on workload type
   ```javascript
   const getRecommendedDuration = (workloadType) => {
     switch (workloadType) {
       case 'OLTP': return { min: '10 minutes', ideal: '30 minutes' };
       case 'OLAP': return { min: '1 hour', ideal: '4 hours' };  // Longer queries
       case 'BATCH': return { min: 'One full batch cycle', ideal: '2-3 batch cycles' };
       default: return { min: '15 minutes', ideal: '1 hour' };
     }
   };
   ```

**UI Enhancement - Experiment Guidance Panel:**
```javascript
{exp.status === 'RUNNING' && (
  <div style={{
    marginTop: '12px',
    padding: '12px',
    background: 'var(--color-primary-soft)',
    borderRadius: '6px',
    fontSize: '12px'
  }}>
    <div style={{ fontWeight: 600, marginBottom: '8px' }}>Experiment Best Practices</div>
    <ul style={{ margin: 0, paddingLeft: '20px', lineHeight: 1.6 }}>
      <li>Wait for workload to stabilize after applying the change</li>
      <li>Recommended duration: {getRecommendedDuration(workloadProfile?.type).ideal}</li>
      <li>Avoid running during maintenance windows or backups</li>
      <li>For A/B comparison, ensure similar traffic patterns</li>
    </ul>
  </div>
)}
```

---

## Phase 2: Accuracy Fixes (P1)

### 2.1 Fix Cumulative Counter Averaging

**File:** `backend/src/main/java/com/dbaagent/service/brain/workload/WorkloadCharacterizationService.java`

**Current Bug:** `getAverageMetric()` averages cumulative counter values, which is meaningless.

**OtterTune Approach:** Uses binned decile values and computes rates between observations.

**Fix Implementation:**

```java
/**
 * Aggregate metrics from multiple snapshots.
 * CRITICAL: Compute deltas for cumulative counters, not raw averages.
 */
private Map<String, List<Double>> aggregateMetrics(List<WorkloadMetricsSnapshot> snapshots) {
    Map<String, List<Double>> history = new HashMap<>();

    // Cumulative counters that need delta computation
    Set<String> cumulativeCounters = Set.of(
        "total_seq_scan", "total_idx_scan", "total_tup_ins", "total_tup_upd",
        "total_tup_del", "xact_commit", "xact_rollback", "slow_queries", "temp_files",
        // MySQL equivalents
        "Com_select", "Com_insert", "Com_update", "Com_delete",
        "Innodb_rows_read", "Innodb_rows_inserted"
    );

    // Gauge metrics that can be averaged directly
    Set<String> gaugeMetrics = Set.of(
        "read_write_ratio", "cache_hit_ratio", "innodb_buffer_hit_ratio", "conn_active"
    );

    // Sort snapshots by time (oldest first) for delta computation
    List<WorkloadMetricsSnapshot> sortedSnapshots = new ArrayList<>(snapshots);
    sortedSnapshots.sort(Comparator.comparing(WorkloadMetricsSnapshot::getCollectedAt));

    WorkloadMetricsSnapshot prevSnapshot = null;

    for (WorkloadMetricsSnapshot snapshot : sortedSnapshots) {
        Map<String, Object> metrics = snapshot.getRawMetrics();
        if (metrics == null) continue;

        long intervalSeconds = 1;
        if (prevSnapshot != null && prevSnapshot.getCollectedAt() != null && snapshot.getCollectedAt() != null) {
            intervalSeconds = Math.max(1,
                java.time.Duration.between(prevSnapshot.getCollectedAt(), snapshot.getCollectedAt()).getSeconds());
        }

        for (String key : CLASSIFICATION_METRICS) {
            Object value = metrics.get(key);
            if (!(value instanceof Number)) continue;

            double numValue = ((Number) value).doubleValue();

            if (cumulativeCounters.contains(key)) {
                // Compute rate (delta / interval) for cumulative counters
                if (prevSnapshot != null && prevSnapshot.getRawMetrics() != null) {
                    Object prevValue = prevSnapshot.getRawMetrics().get(key);
                    if (prevValue instanceof Number) {
                        double delta = numValue - ((Number) prevValue).doubleValue();
                        // Handle counter resets (negative delta)
                        if (delta >= 0) {
                            double rate = delta / intervalSeconds;
                            history.computeIfAbsent(key + "_rate", k -> new ArrayList<>()).add(rate);
                        }
                    }
                }
            } else if (gaugeMetrics.contains(key)) {
                // Gauge metrics can be added directly
                history.computeIfAbsent(key, k -> new ArrayList<>()).add(numValue);
            }
        }

        prevSnapshot = snapshot;
    }

    // Also compute derived metrics
    computeDerivedMetrics(history);

    return history;
}

/**
 * Compute derived metrics from raw metrics.
 */
private void computeDerivedMetrics(Map<String, List<Double>> history) {
    // Compute index usage ratio from scan rates
    List<Double> seqScanRates = history.getOrDefault("total_seq_scan_rate", List.of());
    List<Double> idxScanRates = history.getOrDefault("total_idx_scan_rate", List.of());

    if (!seqScanRates.isEmpty() && !idxScanRates.isEmpty()) {
        List<Double> indexRatios = new ArrayList<>();
        int minSize = Math.min(seqScanRates.size(), idxScanRates.size());
        for (int i = 0; i < minSize; i++) {
            double total = seqScanRates.get(i) + idxScanRates.get(i);
            if (total > 0) {
                indexRatios.add(idxScanRates.get(i) / total);
            }
        }
        if (!indexRatios.isEmpty()) {
            history.put("index_usage_ratio", indexRatios);
        }
    }

    // Compute write composition ratio
    List<Double> insertRates = history.getOrDefault("total_tup_ins_rate", List.of());
    List<Double> updateRates = history.getOrDefault("total_tup_upd_rate", List.of());

    if (!insertRates.isEmpty() && !updateRates.isEmpty()) {
        List<Double> insertRatios = new ArrayList<>();
        int minSize = Math.min(insertRates.size(), updateRates.size());
        for (int i = 0; i < minSize; i++) {
            double total = insertRates.get(i) + updateRates.get(i);
            if (total > 0) {
                insertRatios.add(insertRates.get(i) / total);
            }
        }
        if (!insertRatios.isEmpty()) {
            history.put("insert_vs_update_ratio", insertRatios);
        }
    }
}
```

**Update `classifyWorkloadType()` to use rate-based metrics:**
```java
private WorkloadProfile.WorkloadType classifyWorkloadType(Map<String, List<Double>> metricHistory) {
    // Use rate-based metrics instead of cumulative values
    double seqScanRate = getAverageMetric(metricHistory, "total_seq_scan_rate", 0);
    double idxScanRate = getAverageMetric(metricHistory, "total_idx_scan_rate", 0);
    double indexUsageRatio = getAverageMetric(metricHistory, "index_usage_ratio", 0.5);

    double insertRate = getAverageMetric(metricHistory, "total_tup_ins_rate", 0);
    double updateRate = getAverageMetric(metricHistory, "total_tup_upd_rate", 0);
    double deleteRate = getAverageMetric(metricHistory, "total_tup_del_rate", 0);
    double totalWriteRate = insertRate + updateRate + deleteRate;

    double commitRate = getAverageMetric(metricHistory, "xact_commit_rate", 0);
    double readWriteRatio = getAverageMetric(metricHistory, "read_write_ratio", 0.5);

    // ... rest of classification logic using rates
}
```

---

### 2.2 Extend Cardinality Recommendations to PostgreSQL

**File:** `backend/src/main/java/com/dbaagent/service/brain/query/AdaptivePlanScoringService.java`

**Current Bug:** `getCardinalityAccuracyStats()` emits MySQL-focused actions only.

**Fix Implementation:**

```java
public Map<String, Object> getCardinalityAccuracyStats(String connectionId) {
    Map<String, Object> stats = new HashMap<>();

    // ... existing accuracy calculation ...

    // Generate database-specific recommendations
    ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
    String dbType = providerRegistry.getCanonicalName(connection.getDbType());

    List<Map<String, Object>> recommendations = generateCardinalityRecommendations(
        dbType, perTableAccuracy, topOffenders);

    stats.put("recommendations", recommendations);
    return stats;
}

private List<Map<String, Object>> generateCardinalityRecommendations(
        String dbType, Map<String, Double> perTableAccuracy, List<String> topOffenders) {

    List<Map<String, Object>> recommendations = new ArrayList<>();

    if ("postgres".equals(dbType)) {
        // PostgreSQL-specific recommendations
        if (!topOffenders.isEmpty()) {
            // ANALYZE TABLE recommendation
            String tables = String.join(", ", topOffenders);
            recommendations.add(Map.of(
                "title", "Run ANALYZE on High-Error Tables",
                "priority", "HIGH",
                "description", String.format(
                    "Tables %s have poor cardinality estimates. Running ANALYZE updates " +
                    "planner statistics for better query plans.", tables),
                "sql", String.format("ANALYZE %s;", tables),
                "impact", "Improved query plans, potentially 10-50% faster queries"
            ));

            // Increase statistics target for problematic columns
            recommendations.add(Map.of(
                "title", "Increase Statistics Target",
                "priority", "MEDIUM",
                "description", "For columns with skewed data distributions, increase " +
                    "statistics target from default 100 to 500-1000 for better estimates.",
                "sql", """
                    -- For each high-error column:
                    ALTER TABLE table_name ALTER COLUMN column_name SET STATISTICS 500;
                    ANALYZE table_name (column_name);

                    -- Check current statistics target:
                    SELECT attname, attstattarget
                    FROM pg_attribute
                    WHERE attrelid = 'table_name'::regclass;
                    """,
                "impact", "Better handling of skewed data distributions"
            ));

            // Extended statistics for correlated columns
            recommendations.add(Map.of(
                "title", "Create Extended Statistics",
                "priority", "MEDIUM",
                "description", "If queries filter on multiple correlated columns, " +
                    "PostgreSQL may underestimate cardinality. Extended statistics help.",
                "sql", """
                    -- Create multivariate statistics for correlated columns:
                    CREATE STATISTICS stat_name (dependencies, ndistinct, mcv)
                    ON column1, column2 FROM table_name;
                    ANALYZE table_name;

                    -- View extended statistics:
                    SELECT * FROM pg_statistic_ext;
                    """,
                "impact", "Better estimates for multi-column predicates"
            ));
        }

        // Check for bloated tables affecting estimates
        recommendations.add(Map.of(
            "title", "Check for Table Bloat",
            "priority", "LOW",
            "description", "Table bloat can cause inaccurate row estimates. " +
                "Consider VACUUM FULL for severely bloated tables.",
            "sql", """
                -- Check estimated vs actual rows:
                SELECT schemaname, relname,
                       n_live_tup as estimated_rows,
                       pg_relation_size(relid) as table_size
                FROM pg_stat_user_tables
                WHERE n_dead_tup > n_live_tup * 0.1
                ORDER BY n_dead_tup DESC;
                """,
            "impact", "More accurate table size estimates"
        ));

    } else if ("mysql".equals(dbType)) {
        // MySQL-specific recommendations (existing)
        if (!topOffenders.isEmpty()) {
            String tables = String.join(", ", topOffenders);
            recommendations.add(Map.of(
                "title", "Run ANALYZE TABLE",
                "priority", "HIGH",
                "description", String.format(
                    "Tables %s have poor cardinality estimates. " +
                    "ANALYZE TABLE updates index statistics.", tables),
                "sql", String.format("ANALYZE TABLE %s;", tables),
                "impact", "Improved query optimizer decisions"
            ));

            // Histogram recommendations for MySQL 8.0+
            recommendations.add(Map.of(
                "title", "Create Histograms for Skewed Columns",
                "priority", "MEDIUM",
                "description", "MySQL 8.0+ supports histograms for columns with " +
                    "non-uniform data distributions.",
                "sql", """
                    -- Create histogram with 100 buckets:
                    ANALYZE TABLE table_name UPDATE HISTOGRAM ON column_name WITH 100 BUCKETS;

                    -- View histograms:
                    SELECT * FROM information_schema.COLUMN_STATISTICS
                    WHERE TABLE_NAME = 'table_name';
                    """,
                "impact", "Better estimates for filtered queries on skewed data"
            ));

            // InnoDB statistics persistence
            recommendations.add(Map.of(
                "title", "Enable Persistent Statistics",
                "priority", "LOW",
                "description", "Ensure InnoDB statistics persist across restarts.",
                "sql", """
                    -- Check current setting:
                    SHOW VARIABLES LIKE 'innodb_stats_persistent';

                    -- Enable if OFF:
                    SET GLOBAL innodb_stats_persistent = ON;
                    """,
                "impact", "Consistent query plans after restarts"
            ));
        }
    }

    return recommendations;
}
```

---

### 2.3 Render Plan Pattern SQL and Examples

**File:** `src/components/tabs/Brain/QueryIntelligencePanel.js:914-951`

**Current Bug:** Only renders `message` and `recommendation`, hiding `sql`, `example`, `reasoning` fields.

**Fix Implementation:**

```javascript
{pattern.suggestions && pattern.suggestions.length > 0 && (
  <div style={{ marginTop: '12px' }}>
    <div style={{ fontSize: '12px', fontWeight: 600, marginBottom: '8px', color: 'var(--color-dark-1)' }}>
      Optimization Suggestions:
    </div>
    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
      {pattern.suggestions.slice(0, 3).map((suggestion, sIdx) => (
        <div key={sIdx} style={{
          padding: '12px',
          background: suggestion.severity === 'HIGH' ? 'var(--color-danger-soft)' :
                     suggestion.severity === 'MEDIUM' ? 'var(--color-warning-soft)' : 'var(--color-primary-soft)',
          borderLeft: `3px solid ${
            suggestion.severity === 'HIGH' ? 'var(--color-danger)' :
            suggestion.severity === 'MEDIUM' ? 'var(--color-warning)' : 'var(--color-primary)'
          }`,
          borderRadius: '4px'
        }}>
          {/* Header with type and priority */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
            <span style={{
              fontSize: '10px',
              padding: '2px 6px',
              background: 'rgba(0,0,0,0.1)',
              borderRadius: '3px',
              fontWeight: 600,
              textTransform: 'uppercase'
            }}>
              {suggestion.type || 'ISSUE'}
            </span>
            {suggestion.priority && (
              <span style={{ fontSize: '11px', color: 'var(--color-light-6)' }}>
                Priority: {suggestion.priority}
              </span>
            )}
            {suggestion.estimatedImprovement && (
              <span style={{
                fontSize: '11px',
                color: 'var(--color-success)',
                marginLeft: 'auto'
              }}>
                +{suggestion.estimatedImprovement}% expected
              </span>
            )}
          </div>

          {/* Message / Description */}
          <div style={{ fontWeight: 500, marginBottom: '4px', fontSize: '13px' }}>
            {suggestion.message || suggestion.type}
          </div>

          {/* Recommendation */}
          {suggestion.recommendation && (
            <div style={{ color: 'var(--color-light-6)', fontSize: '12px', marginBottom: '8px' }}>
              {suggestion.recommendation}
            </div>
          )}

          {/* Reasoning (for AI-generated suggestions) */}
          {suggestion.reasoning && (
            <div style={{
              fontSize: '12px',
              color: 'var(--color-dark-1)',
              fontStyle: 'italic',
              marginBottom: '8px'
            }}>
              {suggestion.reasoning}
            </div>
          )}

          {/* SQL Code Block (for INDEX type) */}
          {(suggestion.sql || suggestion.suggestedSQL) && (
            <div style={{ marginTop: '8px' }}>
              <div style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: '4px'
              }}>
                <span style={{ fontSize: '11px', fontWeight: 500, color: 'var(--color-light-6)' }}>
                  {suggestion.type === 'INDEX' ? 'Create Index' : 'SQL'}
                </span>
                <button
                  onClick={() => handleCopySql(suggestion.sql || suggestion.suggestedSQL, `pattern-${idx}-${sIdx}`)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '4px',
                    padding: '3px 6px',
                    background: copiedSql === `pattern-${idx}-${sIdx}` ? 'var(--color-success-soft)' : 'var(--color-light-2)',
                    color: copiedSql === `pattern-${idx}-${sIdx}` ? 'var(--color-success)' : 'var(--color-dark-1)',
                    border: 'none',
                    borderRadius: '3px',
                    fontSize: '10px',
                    cursor: 'pointer'
                  }}
                >
                  {copiedSql === `pattern-${idx}-${sIdx}` ? <Check size={10} /> : <Copy size={10} />}
                  {copiedSql === `pattern-${idx}-${sIdx}` ? 'Copied' : 'Copy'}
                </button>
              </div>
              <pre style={{
                padding: '8px',
                background: 'var(--color-dark-1)',
                color: 'var(--color-light-3)',
                borderRadius: '4px',
                fontSize: '11px',
                fontFamily: 'monospace',
                overflow: 'auto',
                margin: 0,
                whiteSpace: 'pre-wrap'
              }}>
                {suggestion.sql || suggestion.suggestedSQL}
              </pre>
            </div>
          )}

          {/* Example Query Rewrite */}
          {suggestion.example && suggestion.type === 'QUERY_REWRITE' && (
            <div style={{ marginTop: '8px' }}>
              <span style={{ fontSize: '11px', fontWeight: 500, color: 'var(--color-light-6)' }}>
                Optimized Query:
              </span>
              <pre style={{
                marginTop: '4px',
                padding: '8px',
                background: 'var(--color-success-soft)',
                color: 'var(--color-success)',
                borderRadius: '4px',
                fontSize: '11px',
                fontFamily: 'monospace',
                overflow: 'auto',
                margin: 0,
                whiteSpace: 'pre-wrap'
              }}>
                {suggestion.example}
              </pre>
            </div>
          )}

          {/* Index columns for INDEX type */}
          {suggestion.columns && (
            <div style={{ marginTop: '6px', fontSize: '11px' }}>
              <span style={{ color: 'var(--color-light-6)' }}>Columns: </span>
              <span style={{ fontFamily: 'monospace' }}>
                {Array.isArray(suggestion.columns) ? suggestion.columns.join(', ') : suggestion.columns}
              </span>
            </div>
          )}
        </div>
      ))}
      {pattern.suggestions.length > 3 && (
        <div style={{ fontSize: '11px', color: 'var(--color-light-6)', fontStyle: 'italic' }}>
          +{pattern.suggestions.length - 3} more suggestions
        </div>
      )}
    </div>
  </div>
)}

{/* Show optimized query if available */}
{pattern.optimizedQuery && (
  <div style={{ marginTop: '12px' }}>
    <div style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: '4px'
    }}>
      <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--color-success)' }}>
        Optimized Query Available
      </span>
      <button
        onClick={() => handleCopySql(pattern.optimizedQuery, `optimized-${idx}`)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '4px',
          padding: '4px 8px',
          background: copiedSql === `optimized-${idx}` ? 'var(--color-success-soft)' : 'var(--color-light-2)',
          color: copiedSql === `optimized-${idx}` ? 'var(--color-success)' : 'var(--color-dark-1)',
          border: 'none',
          borderRadius: '4px',
          fontSize: '11px',
          cursor: 'pointer'
        }}
      >
        {copiedSql === `optimized-${idx}` ? <Check size={12} /> : <Copy size={12} />}
        {copiedSql === `optimized-${idx}` ? 'Copied' : 'Copy'}
      </button>
    </div>
    <pre style={{
      padding: '10px',
      background: 'var(--color-success-soft)',
      border: '1px solid var(--color-success)',
      color: 'var(--color-dark-1)',
      borderRadius: '6px',
      fontSize: '12px',
      fontFamily: 'monospace',
      overflow: 'auto',
      margin: 0,
      whiteSpace: 'pre-wrap'
    }}>
      {pattern.optimizedQuery}
    </pre>
  </div>
)}

{/* Explanation */}
{pattern.explanation && (
  <div style={{
    marginTop: '12px',
    padding: '10px',
    background: 'var(--color-light-2)',
    borderRadius: '6px',
    fontSize: '12px',
    color: 'var(--color-dark-1)',
    whiteSpace: 'pre-wrap'
  }}>
    {pattern.explanation}
  </div>
)}
```

---

## Phase 3: UI/UX Improvements (P2)

### 3.1 Add Pattern Feedback Buttons

**File:** `src/components/tabs/Brain/QueryIntelligencePanel.js`

**Purpose:** Enable the effectiveness learning loop by collecting user feedback.

```javascript
// Add to pattern card footer
<div style={{
  marginTop: '12px',
  paddingTop: '12px',
  borderTop: '1px solid var(--color-light-2)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between'
}}>
  <div style={{ fontSize: '11px', color: 'var(--color-light-6)' }}>
    Was this optimization helpful?
  </div>
  <div style={{ display: 'flex', gap: '8px' }}>
    <button
      onClick={() => handlePatternFeedback(pattern.id, true)}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '4px',
        padding: '4px 8px',
        background: pattern.userFeedback === 'success' ? 'var(--color-success-soft)' : 'var(--color-light-2)',
        color: pattern.userFeedback === 'success' ? 'var(--color-success)' : 'var(--color-dark-1)',
        border: 'none',
        borderRadius: '4px',
        fontSize: '11px',
        cursor: 'pointer'
      }}
    >
      <ThumbsUp size={12} />
      Helpful
    </button>
    <button
      onClick={() => handlePatternFeedback(pattern.id, false)}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '4px',
        padding: '4px 8px',
        background: pattern.userFeedback === 'failed' ? 'var(--color-danger-soft)' : 'var(--color-light-2)',
        color: pattern.userFeedback === 'failed' ? 'var(--color-danger)' : 'var(--color-dark-1)',
        border: 'none',
        borderRadius: '4px',
        fontSize: '11px',
        cursor: 'pointer'
      }}
    >
      <ThumbsDown size={12} />
      Not helpful
    </button>
  </div>
</div>
```

**Add handler:**
```javascript
const handlePatternFeedback = async (patternId, wasSuccessful) => {
  try {
    await brainAPI.recordPatternFeedback(patternId, wasSuccessful);
    // Update local state to show feedback recorded
    setPatterns(prev => prev.map(p =>
      p.id === patternId
        ? { ...p, userFeedback: wasSuccessful ? 'success' : 'failed' }
        : p
    ));
  } catch (err) {
    setError('Failed to record feedback');
  }
};
```

**Add API method (CORRECTED - uses @RequestParam, not JSON body):**
```javascript
// In client.js brainAPI
// Backend expects: POST /patterns/{id}/feedback?wasSuccessful=true
recordPatternFeedback: (patternId, wasSuccessful) =>
  apiClient.post(`/brain/patterns/${patternId}/feedback`, null, {
    params: { wasSuccessful }
  }),
```

---

### 3.2 Add ML Health Status Widget

**File:** `src/components/tabs/Brain/BrainOverview.js` (or create new component)

**Purpose:** Surface `GET /api/brain/ml-overview/{connectionId}` data to show Brain 2.0 readiness.

```javascript
// MLHealthWidget.js
export function MLHealthWidget({ connectionId }) {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchStatus() {
      try {
        const data = await brainAPI.getMLOverview(connectionId);
        setStatus(data);
      } catch (err) {
        console.error('Failed to fetch ML status:', err);
      } finally {
        setLoading(false);
      }
    }
    if (connectionId) fetchStatus();
  }, [connectionId]);

  if (loading) return <Loader size={16} />;
  if (!status) return null;

  // CORRECTED: Response returns nested objects, not booleans
  // Response shape: { workloadProfile: {...}, configTuning: {...}, queryIntelligence: {...} }
  const steps = [
    {
      key: 'workload',
      label: 'Workload Classified',
      // workloadProfile is an object with type, subtype, confidence, lastUpdated
      done: !!status.workloadProfile?.type,
      detail: status.workloadProfile?.type
        ? `${status.workloadProfile.type} (${status.workloadProfile.confidence?.toFixed(0)}%)`
        : null,
      action: 'Collect workload metrics',
      link: '/brain?tab=workload'
    },
    {
      key: 'knobs',
      label: 'Knobs Ranked',
      // configTuning is an object with knobsRanked, experimentSuccessRate
      done: status.configTuning?.knobsRanked > 0,
      detail: status.configTuning?.knobsRanked
        ? `${status.configTuning.knobsRanked} knobs ranked`
        : null,
      action: 'Identify important knobs',
      link: '/brain?tab=config'
    },
    {
      key: 'stats',
      label: 'Statistics Available',
      // queryIntelligence is an object with statisticsCount, calibrationStatus, patternStats
      done: status.queryIntelligence?.statisticsCount > 0,
      detail: status.queryIntelligence?.statisticsCount
        ? `${status.queryIntelligence.statisticsCount} columns analyzed`
        : null,
      action: 'Analyze key columns',
      link: '/brain?tab=overview'
    },
    {
      key: 'patterns',
      label: 'Patterns Learned',
      // patternStats contains { total, reliable, avgScore }
      done: status.queryIntelligence?.patternStats?.total > 0,
      detail: status.queryIntelligence?.patternStats?.total
        ? `${status.queryIntelligence.patternStats.total} patterns (${status.queryIntelligence.patternStats.reliable} reliable)`
        : null,
      action: 'Ingest slow queries',
      link: '/brain?tab=query-intelligence'
    }
  ];

  const completedCount = steps.filter(s => s.done).length;
  const readinessPercent = (completedCount / steps.length) * 100;

  return (
    <div style={{
      padding: '16px',
      background: 'var(--color-light-1)',
      borderRadius: '8px',
      marginBottom: '16px'
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
        <h4 style={{ margin: 0, fontSize: '14px', fontWeight: 600 }}>
          Brain 2.0 Learning Status
        </h4>
        <span style={{
          padding: '4px 8px',
          borderRadius: '4px',
          fontSize: '12px',
          fontWeight: 500,
          background: readinessPercent === 100 ? 'var(--color-success-soft)' : 'var(--color-warning-soft)',
          color: readinessPercent === 100 ? 'var(--color-success)' : 'var(--color-warning)'
        }}>
          {readinessPercent.toFixed(0)}% Ready
        </span>
      </div>

      {/* Progress bar */}
      <div style={{
        height: '6px',
        background: 'var(--color-light-3)',
        borderRadius: '3px',
        marginBottom: '16px',
        overflow: 'hidden'
      }}>
        <div style={{
          width: `${readinessPercent}%`,
          height: '100%',
          background: readinessPercent === 100 ? 'var(--color-success)' : 'var(--color-primary)',
          borderRadius: '3px',
          transition: 'width 0.3s ease'
        }} />
      </div>

      {/* Steps */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {steps.map((step, idx) => (
          <div key={step.key} style={{
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            padding: '8px',
            background: step.done ? 'var(--color-success-soft)' : 'white',
            borderRadius: '6px',
            border: '1px solid var(--color-light-2)'
          }}>
            {step.done ? (
              <CheckCircle size={16} style={{ color: 'var(--color-success)' }} />
            ) : (
              <div style={{
                width: '16px',
                height: '16px',
                borderRadius: '50%',
                border: '2px solid var(--color-light-4)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '10px',
                fontWeight: 600,
                color: 'var(--color-light-6)'
              }}>
                {idx + 1}
              </div>
            )}
            <div style={{ flex: 1 }}>
              <span style={{
                fontSize: '13px',
                fontWeight: 500,
                color: step.done ? 'var(--color-success)' : 'var(--color-dark-1)'
              }}>
                {step.label}
              </span>
              {step.done && step.detail && (
                <div style={{ fontSize: '11px', color: 'var(--color-light-6)', marginTop: '2px' }}>
                  {step.detail}
                </div>
              )}
            </div>
            {!step.done && (
              <button
                onClick={() => window.location.href = step.link}
                style={{
                  padding: '4px 8px',
                  background: 'var(--color-primary)',
                  color: 'white',
                  border: 'none',
                  borderRadius: '4px',
                  fontSize: '11px',
                  cursor: 'pointer'
                }}
              >
                {step.action}
              </button>
            )}
          </div>
        ))}
      </div>

      {/* Workload Profile Details - CORRECTED field names */}
      {status.workloadProfile?.type && (
        <div style={{
          marginTop: '12px',
          padding: '12px',
          background: 'white',
          borderRadius: '6px',
          fontSize: '12px',
          border: '1px solid var(--color-light-2)'
        }}>
          <div style={{ color: 'var(--color-light-6)', marginBottom: '6px' }}>Current Workload Profile</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div style={{ fontWeight: 600, fontSize: '14px' }}>
              {status.workloadProfile.type}
            </div>
            {status.workloadProfile.subtype && (
              <span style={{
                padding: '2px 8px',
                background: 'var(--color-light-2)',
                borderRadius: '4px',
                fontSize: '11px'
              }}>
                {status.workloadProfile.subtype}
              </span>
            )}
            <span style={{
              marginLeft: 'auto',
              color: status.workloadProfile.confidence >= 80 ? 'var(--color-success)' :
                     status.workloadProfile.confidence >= 60 ? 'var(--color-warning)' : 'var(--color-danger)',
              fontWeight: 500
            }}>
              {status.workloadProfile.confidence?.toFixed(0)}% confidence
            </span>
          </div>
          {status.workloadProfile.lastUpdated && (
            <div style={{ fontSize: '10px', color: 'var(--color-light-6)', marginTop: '6px' }}>
              Last updated: {new Date(status.workloadProfile.lastUpdated).toLocaleString()}
            </div>
          )}
        </div>
      )}

      {/* Config Tuning Status */}
      {status.configTuning?.experimentSuccessRate !== undefined && (
        <div style={{
          marginTop: '8px',
          padding: '8px 12px',
          background: 'white',
          borderRadius: '6px',
          fontSize: '11px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          border: '1px solid var(--color-light-2)'
        }}>
          <span style={{ color: 'var(--color-light-6)' }}>Experiment Success Rate</span>
          <span style={{
            fontWeight: 600,
            color: status.configTuning.experimentSuccessRate >= 70 ? 'var(--color-success)' :
                   status.configTuning.experimentSuccessRate >= 40 ? 'var(--color-warning)' : 'var(--color-danger)'
          }}>
            {status.configTuning.experimentSuccessRate.toFixed(0)}%
          </span>
        </div>
      )}
    </div>
  );
}
```

---

## Phase 4: Documentation Updates (P3)

### 4.1 Update CLAUDE.md Brain 2.0 Section

**Replace the current over-claiming language with honest descriptions:**

```markdown
## Brain 2.0: Database Optimization Intelligence

### Architecture Overview

Brain 2.0 provides database optimization recommendations through a hybrid approach:
- **Workload Analysis**: Statistical classification using threshold-based heuristics (not ML clustering)
- **Knob Ranking**: Impact scoring based on category defaults and known-good patterns
- **Configuration Recommendations**: LLM-powered analysis with database context
- **Query Pattern Learning**: EXPLAIN plan caching with effectiveness tracking

### What Brain 2.0 IS:
- A practical, production-ready tuning assistant
- LLM-enhanced recommendations with database-specific context
- Learning from user feedback and historical experiments
- Cross-workload knowledge sharing via fingerprint similarity

### What Brain 2.0 is NOT (yet):
- True Gaussian Process regression (uses LLM reasoning instead)
- Factor analysis with k-means clustering (uses threshold heuristics)
- Lasso regression for knob selection (uses categorical ranking)

### Relationship to Research

Brain 2.0 is **inspired by** but does not fully implement:
- [OtterTune (CMU, 2017)](https://db.cs.cmu.edu/papers/2017/p1009-van-aken.pdf) - Automatic DBMS tuning
- [optd (CMU)](https://github.com/cmu-db/optd) - Adaptive query optimization

Key differences from OtterTune:
| Feature | OtterTune | DBA-Agent Brain 2.0 |
|---------|-----------|---------------------|
| Workload characterization | Factor analysis + k-means | Threshold-based classification |
| Knob identification | Lasso regression | Category-based ranking |
| Configuration recommendation | GP regression | LLM with learned context |
| Metric handling | Binned decile values | Rate-based deltas |
| Knowledge transfer | Euclidean distance | Fingerprint similarity |

### Known Limitations

1. **Experiment Metrics**: Currently collecting real metrics from database stats; accuracy depends on workload consistency during observation period
2. **Workload Classification**: Uses simplified heuristics rather than true ML clustering
3. **Knob Rankings**: Based on general best practices rather than measured correlations
4. **Configuration Safety**: Generates SQL commands but does not auto-apply changes

### DBA-Facing Outcomes

| Module | Input | Output | Where to View |
|--------|-------|--------|---------------|
| Workload Characterization | Database metrics over time | OLTP/OLAP/MIXED classification with confidence | Brain → Workload |
| Knob Identification | Database config + workload type | Ranked list of impactful parameters | Brain → Config Tuning |
| Config Recommendations | Knobs + workload + history | Specific value recommendations with apply/rollback SQL | Brain → Config Tuning |
| Query Intelligence | Slow query history | Cardinality accuracy + plan patterns | Brain → Query Intelligence |

### Trust Model

- **Confidence scores** indicate certainty of recommendations (based on data availability)
- **All config changes require manual application** - we generate SQL, DBA executes
- **Experiments track before/after metrics** to validate recommendations
- **User feedback** improves pattern effectiveness scores over time
- **Recommendations should be re-evaluated** when workload changes significantly
```

---

## Phase 5: Future Enhancements (Research-Aligned)

### 5.1 Implement True Factor Analysis (Optional)

If you want to align more closely with OtterTune:

```java
/**
 * Factor Analysis for metric dimensionality reduction.
 *
 * OtterTune approach: Reduces 131 MySQL metrics to ~9 by identifying
 * underlying factors and selecting one representative per cluster.
 */
public class MetricFactorAnalysis {

    /**
     * Perform factor analysis on metric observations.
     *
     * @param observations Matrix where rows = metrics, columns = observations
     * @param numFactors Number of factors to extract
     * @return Factor loadings matrix
     */
    public double[][] extractFactors(double[][] observations, int numFactors) {
        // 1. Standardize the data (zero mean, unit variance)
        double[][] standardized = standardize(observations);

        // 2. Compute correlation matrix
        double[][] correlation = computeCorrelationMatrix(standardized);

        // 3. Perform eigendecomposition
        EigenDecomposition eigen = new EigenDecomposition(correlation);

        // 4. Extract top factors
        double[][] loadings = new double[observations.length][numFactors];
        for (int i = 0; i < numFactors; i++) {
            double[] eigenvector = eigen.getEigenvector(i);
            double eigenvalue = Math.sqrt(eigen.getEigenvalue(i));
            for (int j = 0; j < observations.length; j++) {
                loadings[j][i] = eigenvector[j] * eigenvalue;
            }
        }

        return loadings;
    }

    /**
     * Cluster metrics using k-means on factor loadings.
     * Select one representative per cluster (closest to centroid).
     */
    public List<String> selectRepresentativeMetrics(
            List<String> metricNames,
            double[][] factorLoadings,
            int numClusters) {

        // K-means clustering
        KMeans kmeans = new KMeans(numClusters);
        int[] assignments = kmeans.cluster(factorLoadings);

        // Find centroid of each cluster and select closest metric
        List<String> representatives = new ArrayList<>();
        for (int c = 0; c < numClusters; c++) {
            double[] centroid = computeClusterCentroid(factorLoadings, assignments, c);
            int closest = findClosestPoint(factorLoadings, assignments, c, centroid);
            representatives.add(metricNames.get(closest));
        }

        return representatives;
    }
}
```

### 5.2 Implement GP Regression Alternative

**Based on 2021 paper insights:** GP doesn't scale well; SMAC (Random Forest) often works better.

```java
/**
 * Configuration recommendation using Random Forest (SMAC-inspired).
 *
 * Advantages over GP:
 * - Handles categorical knobs natively
 * - Scales better to large configuration spaces
 * - More robust to noisy observations
 */
public class RandomForestConfigRecommender {

    private RandomForest model;
    private List<ConfigurationObservation> observations;

    public void train(List<ConfigurationObservation> observations) {
        this.observations = observations;

        // Convert observations to feature matrix
        double[][] features = observations.stream()
            .map(this::configToFeatures)
            .toArray(double[][]::new);

        double[] targets = observations.stream()
            .mapToDouble(o -> -o.getLatencyP50())  // Minimize latency
            .toArray();

        this.model = new RandomForest(100);  // 100 trees
        this.model.fit(features, targets);
    }

    public Map<String, String> recommend(List<KnobRanking> knobs) {
        // Generate candidate configurations
        List<Map<String, String>> candidates = generateCandidates(knobs, 1000);

        // Predict performance for each
        Map<String, String> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Map<String, String> candidate : candidates) {
            double[] features = configToFeatures(candidate);
            double predicted = model.predict(features);

            // Add exploration bonus (uncertainty)
            double uncertainty = model.predictVariance(features);
            double score = predicted + 0.1 * Math.sqrt(uncertainty);

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best;
    }
}
```

---

## Implementation Timeline

| Phase | Tasks | Estimated Effort | Dependencies |
|-------|-------|------------------|--------------|
| **Phase 1** | P0 fixes (metrics, UI fields, entity) | 2-3 days | None |
| **Phase 2** | P1 fixes (rate metrics, cardinality) | 3-4 days | Phase 1 |
| **Phase 3** | P2 UX (feedback, ML health) | 2-3 days | Phase 1 |
| **Phase 4** | Documentation | 1 day | Phase 1-3 |
| **Phase 5** | Research-aligned enhancements | TBD | All above |

---

## Testing Strategy

### Unit Tests

```java
// Test rate-based metric computation
@Test
void testCumulativeCounterDeltas() {
    WorkloadMetricsSnapshot snap1 = createSnapshot(Map.of("xact_commit", 1000L), t1);
    WorkloadMetricsSnapshot snap2 = createSnapshot(Map.of("xact_commit", 1100L), t2);

    Map<String, List<Double>> history = service.aggregateMetrics(List.of(snap1, snap2));

    assertThat(history.get("xact_commit_rate")).hasSize(1);
    assertThat(history.get("xact_commit_rate").get(0)).isCloseTo(100.0 / intervalSeconds, within(0.1));
}

// Test experiment field mapping
@Test
void testTuningExperimentDTOMapping() {
    TuningExperiment exp = TuningExperiment.builder()
        .knobChanges(Map.of("shared_buffers", Map.of("old", "128MB", "new", "256MB")))
        .overallImprovementPercent(15.5)
        .build();

    TuningExperimentDTO dto = mapper.toDTO(exp);

    assertThat(dto.getKnobName()).isEqualTo("shared_buffers");
    assertThat(dto.getOriginalValue()).isEqualTo("128MB");
    assertThat(dto.getTargetValue()).isEqualTo("256MB");
    assertThat(dto.getImprovementPercent()).isEqualTo(15.5);
}
```

### Integration Tests

```java
@Test
void testEndToEndConfigTuning() {
    // 1. Collect metrics
    WorkloadMetricsSnapshot snapshot = metricsCollector.collectMetrics(connectionId);
    assertThat(snapshot.getRawMetrics()).isNotEmpty();

    // 2. Characterize workload
    WorkloadProfile profile = workloadService.characterizeWorkload(connectionId);
    assertThat(profile.getWorkloadType()).isNotNull();

    // 3. Identify knobs
    List<KnobRanking> knobs = knobService.identifyKnobs(connectionId);
    assertThat(knobs).isNotEmpty();

    // 4. Generate recommendations
    List<ConfigurationRecommendation> recs = configService.generateRecommendations(connectionId);
    assertThat(recs).allSatisfy(rec -> {
        assertThat(rec.getApplySql()).isNotBlank();
        assertThat(rec.getImpactDescription()).isNotBlank();
    });

    // 5. Create experiment
    TuningExperiment exp = configService.startExperiment(connectionId, knobChanges, null);
    assertThat(exp.getBaselineMetrics()).isNotEmpty();
    assertThat(exp.getBaselineMetrics().get("latency_p50")).isGreaterThan(0);
}
```

---

## References

### OtterTune Papers
- [2017 SIGMOD: Automatic DBMS Tuning Through Large-scale ML](https://db.cs.cmu.edu/papers/2017/p1009-van-aken.pdf)
- [2021 PVLDB: An Inquiry into ML-based Tuning Services](https://db.cs.cmu.edu/papers/2021/p1241-aken.pdf)
- [2019 ICDE: External vs Internal ML Agents](https://db.cs.cmu.edu/papers/2019/pavlo-icde-bulletin2019.pdf)
- [2018 VLDB Demo](https://www.vldb.org/pvldb/vol11/p1910-zhang.pdf)

### optd Resources
- [GitHub Repository](https://github.com/cmu-db/optd)
- [CMU Project Page](https://db.cs.cmu.edu/projects/optd/)

### Additional Resources
- [Database Knob Tuning Survey (Tsinghua)](https://dbgroup.cs.tsinghua.edu.cn/ligl/papers/tuning-survey.pdf)
- [LlamaTune: Sample-Efficient Tuning](https://www.vldb.org/pvldb/vol15/p2953-kanellis.pdf)
- [Morning Paper: OtterTune Summary](https://blog.acolyer.org/2017/08/11/automatic-database-management-system-tuning-through-large-scale-machine-learning/)
