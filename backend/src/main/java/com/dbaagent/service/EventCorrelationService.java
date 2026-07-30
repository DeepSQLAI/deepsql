package com.dbaagent.service;

import com.dbaagent.model.DatabaseEvent;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.DatabaseEventRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Event Correlation Service
 * Links growth anomalies and spikes to deployment/schema change events
 * Implements Sentinel-DBA's "Contextual Attribution" pillar
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventCorrelationService {

    private final DatabaseEventRepository eventRepository;
    private final GrowthAnomalyRepository anomalyRepository;
    private final TableStatsHistoryRepository historyRepository;

    private static final int CORRELATION_WINDOW_HOURS = 24;  // ±24 hours

    /**
     * Correlate an anomaly with nearby events (within ±24 hour window)
     * Updates the anomaly with attributed event information
     */
    public void correlateAnomalyWithEvents(GrowthAnomaly anomaly) {
        log.debug("Correlating anomaly {} for table {} with events", anomaly.getId(), anomaly.getTableName());

        LocalDateTime detectionTime = anomaly.getDetectionTimestamp();
        LocalDateTime windowStart = detectionTime.minusHours(CORRELATION_WINDOW_HOURS);
        LocalDateTime windowEnd = detectionTime.plusHours(CORRELATION_WINDOW_HOURS);

        // Find events within correlation window
        List<DatabaseEvent> events = eventRepository.findEventsInCorrelationWindow(
                anomaly.getConnectionId(),
                windowStart,
                windowEnd
        );

        if (events.isEmpty()) {
            log.debug("No events found in correlation window for anomaly {}", anomaly.getId());
            return;
        }

        // Find most likely event (closest in time, affecting same table)
        DatabaseEvent attributedEvent = findMostLikelyEvent(anomaly, events);

        if (attributedEvent != null) {
            anomaly.setAttributedEventId(attributedEvent.getId());
            anomaly.setAttributedEventDescription(attributedEvent.getSummaryDescription());

            log.info("Attributed anomaly {} to event: {}", anomaly.getId(),
                    attributedEvent.getSummaryDescription());

            anomalyRepository.save(anomaly);
        }
    }

    /**
     * Find the most likely event that caused the anomaly
     * Scoring based on:
     * 1. Time proximity (closer = more likely)
     * 2. Table overlap (event affects same table)
     * 3. Event risk level (high-risk events more likely)
     */
    private DatabaseEvent findMostLikelyEvent(GrowthAnomaly anomaly, List<DatabaseEvent> events) {
        DatabaseEvent bestMatch = null;
        double bestScore = 0.0;

        for (DatabaseEvent event : events) {
            double score = calculateCorrelationScore(anomaly, event);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = event;
            }
        }

        // Only attribute if score is above threshold
        if (bestScore >= 0.5) {  // 50% confidence threshold
            return bestMatch;
        }

        return null;
    }

    /**
     * Calculate correlation score (0.0 to 1.0) between anomaly and event
     */
    private double calculateCorrelationScore(GrowthAnomaly anomaly, DatabaseEvent event) {
        double score = 0.0;

        // 1. Time proximity (max 0.4)
        long hoursDiff = Math.abs(java.time.Duration.between(
                anomaly.getDetectionTimestamp(),
                event.getEventTimestamp()).toHours());

        double timeScore = Math.max(0, (CORRELATION_WINDOW_HOURS - hoursDiff) / (double) CORRELATION_WINDOW_HOURS) * 0.4;
        score += timeScore;

        // 2. Table overlap (max 0.4)
        if (event.getAffectedTables() != null && event.getAffectedTables().contains(anomaly.getTableName())) {
            score += 0.4;  // Direct table match
        }

        // 3. Event risk level (max 0.2)
        if (event.isHighRiskEvent()) {
            score += 0.2;
        } else {
            score += 0.1;
        }

        return score;
    }

    /**
     * Log a deployment event for correlation tracking
     */
    public DatabaseEvent logDeploymentEvent(
            String connectionId,
            String deploymentVersion,
            String deploymentTag,
            List<String> affectedTables,
            String initiatedBy) {

        DatabaseEvent event = DatabaseEvent.builder()
                .connectionId(connectionId)
                .eventType(DatabaseEvent.EventType.DEPLOYMENT)
                .eventName("Deployment " + deploymentVersion)
                .eventTimestamp(LocalDateTime.now())
                .deploymentVersion(deploymentVersion)
                .deploymentTag(deploymentTag)
                .affectedTables(affectedTables)
                .initiatedBy(initiatedBy)
                .description("Application deployment version " + deploymentVersion)
                .build();

        return eventRepository.save(event);
    }

    /**
     * Log a schema change event
     */
    public DatabaseEvent logSchemaChangeEvent(
            String connectionId,
            String tableName,
            String changeType,
            String description,
            String initiatedBy) {

        DatabaseEvent event = DatabaseEvent.builder()
                .connectionId(connectionId)
                .eventType(DatabaseEvent.EventType.SCHEMA_CHANGE)
                .eventName(changeType + " on " + tableName)
                .eventTimestamp(LocalDateTime.now())
                .affectedTables(List.of(tableName))
                .initiatedBy(initiatedBy)
                .description(description)
                .build();

        return eventRepository.save(event);
    }

    /**
     * Log an index creation event
     */
    public DatabaseEvent logIndexEvent(
            String connectionId,
            String tableName,
            String indexName,
            boolean isCreate,
            String initiatedBy) {

        DatabaseEvent.EventType eventType = isCreate ?
                DatabaseEvent.EventType.INDEX_CREATE :
                DatabaseEvent.EventType.INDEX_DROP;

        DatabaseEvent event = DatabaseEvent.builder()
                .connectionId(connectionId)
                .eventType(eventType)
                .eventName((isCreate ? "Create" : "Drop") + " index " + indexName)
                .eventTimestamp(LocalDateTime.now())
                .affectedTables(List.of(tableName))
                .affectedIndexes(List.of(indexName))
                .initiatedBy(initiatedBy)
                .description((isCreate ? "Created" : "Dropped") + " index " + indexName + " on " + tableName)
                .build();

        return eventRepository.save(event);
    }

    /**
     * Log a bulk data operation event
     */
    public DatabaseEvent logBulkDataEvent(
            String connectionId,
            String tableName,
            boolean isLoad,
            long rowCount,
            String initiatedBy) {

        DatabaseEvent.EventType eventType = isLoad ?
                DatabaseEvent.EventType.BULK_LOAD :
                DatabaseEvent.EventType.BULK_DELETE;

        DatabaseEvent event = DatabaseEvent.builder()
                .connectionId(connectionId)
                .eventType(eventType)
                .eventName((isLoad ? "Bulk load" : "Bulk delete") + " on " + tableName)
                .eventTimestamp(LocalDateTime.now())
                .affectedTables(List.of(tableName))
                .rowCountDelta(isLoad ? rowCount : -rowCount)
                .initiatedBy(initiatedBy)
                .description(String.format("%s %,d rows %s table %s",
                        isLoad ? "Loaded" : "Deleted",
                        rowCount,
                        isLoad ? "into" : "from",
                        tableName))
                .build();

        return eventRepository.save(event);
    }

    /**
     * Correlate all recent unattributed anomalies
     * Can be run as a scheduled job
     */
    public void correlateRecentAnomalies(String connectionId) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);  // Last 7 days

        List<GrowthAnomaly> unattributed = anomalyRepository
                .findRecentAnomalies(connectionId, since).stream()
                .filter(a -> a.getAttributedEventId() == null)
                .toList();

        log.info("Correlating {} unattributed anomalies for connection {}", unattributed.size(), connectionId);

        for (GrowthAnomaly anomaly : unattributed) {
            correlateAnomalyWithEvents(anomaly);
        }
    }

    /**
     * Get attribution summary for all anomalies
     */
    public java.util.Map<String, Object> getAttributionSummary(String connectionId) {
        List<GrowthAnomaly> allAnomalies = anomalyRepository
                .findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(connectionId);

        long attributed = allAnomalies.stream()
                .filter(a -> a.getAttributedEventId() != null)
                .count();

        long total = allAnomalies.size();
        double attributionRate = total > 0 ? (attributed / (double) total) * 100 : 0.0;

        return java.util.Map.of(
                "totalAnomalies", total,
                "attributedAnomalies", attributed,
                "unattributedAnomalies", total - attributed,
                "attributionRate", attributionRate
        );
    }
}
