package com.dbaagent.dto;

import java.time.LocalDateTime;

/**
 * Projection interface for SlowQueryHistory without the large analysisData field.
 * Used for list queries to avoid OOM when loading multiple records.
 */
public interface SlowQueryHistorySummary {
    String getId();
    String getConnectionId();
    String getUserId();
    String getTimeRange();
    Double getSlowQueryThresholdMs();
    Long getTotalSlowQueries();
    String getOverallHealth();
    Long getCriticalCount();
    Long getHighCount();
    Double getTotalDatabaseTimeMs();
    LocalDateTime getCreatedAt();
    LocalDateTime getUpdatedAt();
}
