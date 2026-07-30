package com.dbaagent.service;

import com.dbaagent.model.QueryLineage;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.repository.QueryLineageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryLineageRecorderService {

    private final QueryLineageRepository queryLineageRepository;
    private final QueryLineageService queryLineageService;

    @Value("${slow-query.lineage.max-per-analysis:500}")
    private int maxPerAnalysis = 500;

    @Value("${slow-query.lineage.batch-size:200}")
    private int batchSize = 200;

    @Async
    public void recordAsync(
        String connectionId,
        List<SlowQuery> slowQueries,
        String analysisId,
        String analysisTimeRange,
        LocalDateTime analysisDate
    ) {
        record(connectionId, slowQueries, analysisId, analysisTimeRange, analysisDate);
    }

    public void recordSync(
        String connectionId,
        List<SlowQuery> slowQueries,
        String analysisId,
        String analysisTimeRange,
        LocalDateTime analysisDate
    ) {
        record(connectionId, slowQueries, analysisId, analysisTimeRange, analysisDate);
    }

    private void record(
        String connectionId,
        List<SlowQuery> slowQueries,
        String analysisId,
        String analysisTimeRange,
        LocalDateTime analysisDate
    ) {
        if (connectionId == null || slowQueries == null || slowQueries.isEmpty()) {
            return;
        }

        if (analysisId != null) {
            long existing = queryLineageRepository.countByConnectionIdAndAnalysisId(connectionId, analysisId);
            if (existing > 0) {
                log.info("Slow query lineage already exists for analysis {}, skipping", analysisId);
                return;
            }
        }

        int limit = maxPerAnalysis > 0 ? Math.min(maxPerAnalysis, slowQueries.size()) : slowQueries.size();
        if (limit < slowQueries.size()) {
            log.info("Capping slow query lineage recording to {} of {} queries", limit, slowQueries.size());
        }

        List<QueryLineage> batch = new ArrayList<>(Math.min(batchSize, limit));
        Set<String> seenHashes = new HashSet<>();
        int saved = 0;
        int skipped = 0;

        for (int i = 0; i < limit; i++) {
            SlowQuery slowQuery = slowQueries.get(i);
            Optional<QueryLineage> lineageOpt = queryLineageService.buildSlowQueryLineage(
                connectionId,
                slowQuery,
                analysisId,
                analysisTimeRange,
                analysisDate
            );

            if (lineageOpt.isEmpty()) {
                skipped++;
                continue;
            }

            QueryLineage lineage = lineageOpt.get();
            String hash = lineage.getQueryHash();
            if (hash != null && !seenHashes.add(hash)) {
                skipped++;
                continue;
            }

            batch.add(lineage);
            if (batch.size() >= batchSize) {
                queryLineageRepository.saveAll(batch);
                saved += batch.size();
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            queryLineageRepository.saveAll(batch);
            saved += batch.size();
        }

        log.info("Recorded {} slow query lineage entries (skipped {}) for analysis {}",
            saved, skipped, analysisId != null ? analysisId : "unknown");
    }
}
