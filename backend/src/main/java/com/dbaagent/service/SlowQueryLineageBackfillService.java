package com.dbaagent.service;

import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryLineageBackfillService {

    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final QueryLineageRepository queryLineageRepository;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final SlowQueryLineageRecorderService lineageRecorderService;

    @Value("${slow-query.lineage.backfill.enabled:true}")
    private boolean backfillEnabled = true;

    @Value("${slow-query.lineage.backfill.history-limit:50}")
    private int historyLimit = 50;

    public void backfillSlowQueryLineage() {
        if (!backfillEnabled) {
            return;
        }

        List<SlowQueryHistory> histories = slowQueryHistoryRepository.findRecent(
            PageRequest.of(0, historyLimit)
        );

        if (histories.isEmpty()) {
            return;
        }

        int processed = 0;
        for (SlowQueryHistory history : histories) {
            if (history.getId() == null || history.getConnectionId() == null) {
                continue;
            }

            long existing = queryLineageRepository.countByConnectionIdAndAnalysisId(
                history.getConnectionId(),
                history.getId()
            );
            if (existing > 0) {
                continue;
            }

            SlowQueryAnalysis analysis;
            try {
                analysis = slowQueryHistoryService.getAnalysisData(history);
            } catch (Exception e) {
                log.debug("Failed to deserialize slow query history {}: {}", history.getId(), e.getMessage());
                continue;
            }

            if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                continue;
            }

            lineageRecorderService.recordSync(
                history.getConnectionId(),
                analysis.getTopSlowQueries(),
                history.getId(),
                history.getTimeRange(),
                analysis.getAnalysisDate()
            );
            processed++;
        }

        if (processed > 0) {
            log.info("Backfilled slow query lineage for {} history records", processed);
        }
    }
}
