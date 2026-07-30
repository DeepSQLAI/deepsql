package com.dbaagent.service;

import com.dbaagent.model.AnalysisHistory;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.model.SqlUsage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Service for managing EXPLAIN Plan analysis history
 * Handles persistence, retrieval, and cleanup of analysis history
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisHistoryService {

    private final AnalysisHistoryRepository repository;
    private final ObjectMapper objectMapper;
    private final SqlUsageService sqlUsageService;

    private static final int MAX_HISTORY_ITEMS = 20;
    private static final int CLEANUP_BATCH_SIZE = 200;

    /**
     * Save analysis to history
     * Automatically limits to 20 most recent items per connection
     */
    @Transactional
    public AnalysisHistory saveAnalysis(
        String connectionId,
        String query,
        Boolean useAnalyze,
        Map<String, Object> analysisData,
        Integer performanceScore,
        Integer issueCount,
        String userId
    ) {
        try {
            // Convert analysis data to JSON string
            String analysisJson = objectMapper.writeValueAsString(analysisData);

            // Create and save history entry
            String tablesUsed = null;
            String columnsUsed = null;
            try {
                SqlUsage usage = sqlUsageService.parseUsage(connectionId, query);
                if (usage.getTables() != null && !usage.getTables().isEmpty()) {
                    tablesUsed = String.join(",", usage.getTables());
                }
                if (usage.getColumns() != null && !usage.getColumns().isEmpty()) {
                    columnsUsed = String.join(",", usage.getColumns());
                }
            } catch (Exception e) {
                log.debug("Failed to capture EXPLAIN lineage: {}", e.getMessage());
            }

            AnalysisHistory history = AnalysisHistory.builder()
                .connectionId(connectionId)
                .userId(userId)
                .query(query)
                .useAnalyze(useAnalyze)
                .analysisData(analysisJson)
                .performanceScore(performanceScore)
                .issueCount(issueCount)
                .tablesUsed(tablesUsed)
                .columnsUsed(columnsUsed)
                .build();

            history = repository.save(history);
            log.info("Saved analysis history: id={}, connectionId={}, score={}",
                history.getId(), connectionId, performanceScore);

            // Cleanup old entries to maintain limit
            cleanupOldEntries(connectionId);

            return history;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize analysis data to JSON", e);
            throw new RuntimeException("Failed to save analysis history", e);
        }
    }

    /**
     * Get recent analysis history for a connection
     * Returns up to 20 most recent analyses
     */
    @Transactional(readOnly = true)
    public List<AnalysisHistory> getRecentHistory(String connectionId) {
        return repository.findRecentByConnectionId(
            connectionId,
            PageRequest.of(0, MAX_HISTORY_ITEMS)
        );
    }

    /**
     * Get all history for a connection (ordered by most recent)
     */
    @Transactional(readOnly = true)
    public List<AnalysisHistory> getAllHistory(String connectionId) {
        return repository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
    }

    /**
     * Delete a specific history entry
     */
    @Transactional
    public void deleteHistory(String id) {
        repository.deleteById(id);
        log.info("Deleted analysis history: id={}", id);
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
        log.info("Deleted all history for connection: {}, count={}", connectionId, deleted);
    }

    /**
     * Get analysis data as Map for a history entry
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAnalysisDataAsMap(AnalysisHistory history) {
        try {
            return objectMapper.readValue(history.getAnalysisData(), Map.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize analysis data from JSON", e);
            throw new RuntimeException("Failed to read analysis data", e);
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

        log.info("Cleaned up {} old history entries for connection {}", deleted, connectionId);
    }

    /**
     * Get history count for a connection
     */
    @Transactional(readOnly = true)
    public long getHistoryCount(String connectionId) {
        return repository.countByConnectionId(connectionId);
    }
}
