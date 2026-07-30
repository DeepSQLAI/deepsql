package com.dbaagent.service;

import com.dbaagent.dto.SlowQueryHistorySummary;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing Slow Query analysis history
 * Handles persistence, retrieval, and cleanup of slow query history
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryHistoryService {

    private final SlowQueryHistoryRepository repository;
    private final ObjectMapper objectMapper;
    private final SlowQueryLineageRecorderService lineageRecorderService;

    // Run-header (JSON-blob) cap. Bumped from 20 to 35 so the daily analysis
    // job retains a full 30-day window of headers (AI summary + recommendations)
    // alongside the 30-day relational slow_query_run time series. The per-query
    // analytics retention is governed separately by SlowQueryRetentionService.
    private static final int MAX_HISTORY_ITEMS = 35;
    private static final int CLEANUP_BATCH_SIZE = 500;

    /**
     * Save slow query analysis to history
     * Automatically limits to 20 most recent items per connection
     */
    @Transactional
    public SlowQueryHistory saveAnalysis(
        String connectionId,
        SlowQueryAnalysis analysis,
        String userId
    ) {
        try {
            // Configure ObjectMapper to handle LocalDateTime
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            // Convert analysis data to JSON string
            String analysisJson = mapper.writeValueAsString(analysis);

            // Calculate key metrics
            long criticalCount = analysis.getCountBySeverity(SlowQuery.Severity.CRITICAL);
            long highCount = analysis.getCountBySeverity(SlowQuery.Severity.HIGH);

            // Create and save history entry
            SlowQueryHistory history = SlowQueryHistory.builder()
                .connectionId(connectionId)
                .userId(userId)
                .timeRange(analysis.getTimeRange() != null ? analysis.getTimeRange().name() : null)
                .slowQueryThresholdMs(analysis.getSlowQueryThresholdMs())
                .analysisData(analysisJson)
                .totalSlowQueries(analysis.getTotalSlowQueries())
                .overallHealth(analysis.getOverallHealth())
                .criticalCount(criticalCount)
                .highCount(highCount)
                .totalDatabaseTimeMs(analysis.getTotalDatabaseTimeMs())
                .build();

            history = repository.save(history);
            log.info("Saved slow query history: id={}, connectionId={}, health={}, slowQueries={}",
                history.getId(), connectionId, history.getOverallHealth(), history.getTotalSlowQueries());

            recordSlowQueryLineage(connectionId, analysis, history);

            // Cleanup old entries to maintain limit
            cleanupOldEntries(connectionId);

            return history;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize slow query analysis data to JSON", e);
            throw new RuntimeException("Failed to save slow query history", e);
        }
    }

    private void recordSlowQueryLineage(
        String connectionId,
        SlowQueryAnalysis analysis,
        SlowQueryHistory history
    ) {
        if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
            return;
        }

        try {
            lineageRecorderService.recordAsync(
                connectionId,
                analysis.getTopSlowQueries(),
                history.getId(),
                history.getTimeRange(),
                analysis.getAnalysisDate()
            );
        } catch (Exception e) {
            log.debug("Failed to enqueue slow query lineage recording: {}", e.getMessage());
        }
    }

    /**
     * Get recent slow query history summaries for a connection (without large analysisData)
     * Returns up to 20 most recent analyses
     */
    @Transactional(readOnly = true)
    public List<SlowQueryHistorySummary> getRecentHistorySummaries(String connectionId) {
        return repository.findRecentSummariesByConnectionId(
            connectionId,
            PageRequest.of(0, MAX_HISTORY_ITEMS)
        );
    }

    /**
     * Get a single history entry by ID
     */
    @Transactional(readOnly = true)
    public Optional<SlowQueryHistory> getById(String id) {
        return repository.findById(id);
    }

    /**
     * Get recent slow query history for a connection
     * @deprecated Use getRecentHistorySummaries to avoid OOM with large analysisData
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<SlowQueryHistory> getRecentHistory(String connectionId) {
        return repository.findRecentByConnectionId(
            connectionId,
            PageRequest.of(0, MAX_HISTORY_ITEMS)
        );
    }

    /**
     * Get all history summaries for a connection (ordered by most recent)
     */
    @Transactional(readOnly = true)
    public List<SlowQueryHistorySummary> getAllHistorySummaries(String connectionId) {
        return repository.findSummariesByConnectionId(connectionId);
    }

    /**
     * Get all history for a connection (ordered by most recent)
     * @deprecated Use getAllHistorySummaries to avoid OOM
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<SlowQueryHistory> getAllHistory(String connectionId) {
        return repository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
    }

    /**
     * Get the most recent slow query history for a connection (full entity with analysisData)
     * This loads only a single record, so it's safe from OOM
     */
    @Transactional(readOnly = true)
    public Optional<SlowQueryHistory> getLatestHistory(String connectionId) {
        return repository.findFirstByConnectionIdOrderByCreatedAtDesc(connectionId);
    }

    /**
     * Get a specific history entry by ID (full entity with analysisData)
     */
    @Transactional(readOnly = true)
    public Optional<SlowQueryHistory> getHistoryById(String id) {
        return repository.findById(id);
    }

    /**
     * Get history summaries filtered by time range
     */
    @Transactional(readOnly = true)
    public List<SlowQueryHistorySummary> getHistorySummariesByTimeRange(String connectionId, String timeRange) {
        return repository.findSummariesByConnectionIdAndTimeRange(connectionId, timeRange);
    }

    /**
     * Get history filtered by time range
     * @deprecated Use getHistorySummariesByTimeRange to avoid OOM
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<SlowQueryHistory> getHistoryByTimeRange(String connectionId, String timeRange) {
        return repository.findByConnectionIdAndTimeRange(connectionId, timeRange);
    }

    /**
     * Get history filtered by health status
     */
    @Transactional(readOnly = true)
    public List<SlowQueryHistory> getHistoryByHealth(String connectionId, String health) {
        return repository.findByConnectionIdAndHealth(connectionId, health);
    }

    /**
     * Delete a specific history entry
     */
    @Transactional
    public void deleteHistory(String id) {
        repository.deleteById(id);
        log.info("Deleted slow query history: id={}", id);
    }

    /**
     * Delete all history for a connection
     */
    @Transactional
    public void deleteAllForConnection(String connectionId) {
        int deleted = 0;
        while (true) {
            List<String> ids = repository.findIdsByConnectionIdOrderByCreatedAtDesc(
                connectionId, PageRequest.of(0, CLEANUP_BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            repository.deleteAllById(ids);
            deleted += ids.size();
        }
        log.info("Deleted all slow query history for connection: {}, count={}", connectionId, deleted);
    }

    /**
     * Get analysis data as SlowQueryAnalysis object
     */
    public SlowQueryAnalysis getAnalysisData(SlowQueryHistory history) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper.readValue(history.getAnalysisData(), SlowQueryAnalysis.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize slow query analysis data from JSON", e);
            throw new RuntimeException("Failed to read slow query analysis data", e);
        }
    }

    /**
     * Cleanup old entries to maintain maximum limit
     * Keeps only the most recent MAX_HISTORY_ITEMS entries per connection
     */
    @Transactional
    protected void cleanupOldEntries(String connectionId) {
        long total = repository.countByConnectionId(connectionId);
        if (total <= MAX_HISTORY_ITEMS) {
            return;
        }

        int deleted = 0;
        int page = MAX_HISTORY_ITEMS / CLEANUP_BATCH_SIZE;
        int offset = MAX_HISTORY_ITEMS % CLEANUP_BATCH_SIZE;
        boolean firstPage = true;

        while (true) {
            List<String> ids = repository.findIdsByConnectionIdOrderByCreatedAtDesc(
                connectionId, PageRequest.of(page, CLEANUP_BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            if (firstPage && offset > 0) {
                if (offset >= ids.size()) {
                    page++;
                    continue;
                }
                ids = ids.subList(offset, ids.size());
            }
            repository.deleteAllById(ids);
            deleted += ids.size();

            if (ids.size() < CLEANUP_BATCH_SIZE) {
                break;
            }
            page++;
            firstPage = false;
            offset = 0;
        }

        log.info("Cleaned up {} old slow query history entries for connection {}",
            deleted, connectionId);
    }

    /**
     * Get history count for a connection
     */
    @Transactional(readOnly = true)
    public long getHistoryCount(String connectionId) {
        return repository.countByConnectionId(connectionId);
    }
}
