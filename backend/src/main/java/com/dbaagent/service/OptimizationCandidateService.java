package com.dbaagent.service;

import com.dbaagent.model.QueryOptimizationCandidateRun;
import com.dbaagent.repository.QueryOptimizationCandidateRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OptimizationCandidateService {

    private final QueryOptimizationCandidateRunRepository repository;

    @Transactional
    public QueryOptimizationCandidateRun upsertCandidate(
        String connectionId,
        String queryFingerprint,
        String candidateId,
        String candidateSql,
        String planSignature,
        String planText,
        Double estimatedCost,
        Double estimatedRows,
        String warnings
    ) {
        return upsertCandidate(connectionId, queryFingerprint, candidateId, candidateSql,
            planSignature, planText, estimatedCost, estimatedRows, warnings, false);
    }

    public QueryOptimizationCandidateRun upsertCandidate(
        String connectionId,
        String queryFingerprint,
        String candidateId,
        String candidateSql,
        String planSignature,
        String planText,
        Double estimatedCost,
        Double estimatedRows,
        String warnings,
        boolean forceReplaceSql
    ) {
        Optional<QueryOptimizationCandidateRun> existing = repository
            .findByConnectionIdAndQueryFingerprintAndCandidateId(connectionId, queryFingerprint, candidateId);

        QueryOptimizationCandidateRun candidate = existing.orElseGet(() -> QueryOptimizationCandidateRun.builder()
            .connectionId(connectionId)
            .queryFingerprint(queryFingerprint)
            .candidateId(candidateId)
            .createdAt(LocalDateTime.now())
            .status("PREDICTED")
            .build());

        if (candidateSql != null && !candidateSql.isBlank()) {
            String existingSql = candidate.getCandidateSql();
            boolean shouldUpgradeSql = forceReplaceSql
                || existingSql == null || existingSql.isBlank()
                || (existingSql.contains("?") && !candidateSql.contains("?"))
                || (existingSql != null && existingSql.equalsIgnoreCase(candidateSql) && !existingSql.equals(candidateSql));
            if (shouldUpgradeSql) {
                candidate.setCandidateSql(candidateSql);
                // Reset benchmark data when SQL changes substantially
                if (forceReplaceSql && existingSql != null && !existingSql.equals(candidateSql)) {
                    candidate.setBenchmarkMs(null);
                    candidate.setMedianMs(null);
                    candidate.setRunCount(null);
                    candidate.setStatus("PREDICTED");
                }
            }
        }
        if (planSignature != null && !planSignature.isBlank()) {
            candidate.setPlanSignature(planSignature);
        }
        if (planText != null && !planText.isBlank()) {
            candidate.setPlanText(planText);
        }
        if (estimatedCost != null) {
            candidate.setEstimatedCost(estimatedCost);
        }
        if (estimatedRows != null) {
            candidate.setEstimatedRows(estimatedRows);
        }
        if (warnings != null && !warnings.isBlank()) {
            candidate.setWarnings(warnings);
        }
        if (candidate.getStatus() == null || candidate.getStatus().isBlank()) {
            candidate.setStatus("PREDICTED");
        }
        candidate.setUpdatedAt(LocalDateTime.now());

        return repository.save(candidate);
    }

    public List<QueryOptimizationCandidateRun> getCandidates(String connectionId, String queryFingerprint) {
        return repository.findByConnectionIdAndQueryFingerprint(connectionId, queryFingerprint);
    }

    public Optional<QueryOptimizationCandidateRun> getBestMeasuredCandidate(String connectionId, String queryFingerprint) {
        return getCandidates(connectionId, queryFingerprint).stream()
            .filter(c -> c.getMedianMs() != null || c.getBenchmarkMs() != null)
            .min(Comparator.comparingDouble(c -> c.getMedianMs() != null ? c.getMedianMs() : c.getBenchmarkMs()));
    }

    public Optional<QueryOptimizationCandidateRun> getBestPredictedCandidate(String connectionId, String queryFingerprint) {
        return getCandidates(connectionId, queryFingerprint).stream()
            .filter(c -> c.getEstimatedCost() != null)
            .min(Comparator.comparingDouble(QueryOptimizationCandidateRun::getEstimatedCost));
    }

    public Optional<QueryOptimizationCandidateRun> getBestCandidate(String connectionId, String queryFingerprint) {
        Optional<QueryOptimizationCandidateRun> measured = getBestMeasuredCandidate(connectionId, queryFingerprint);
        if (measured.isPresent()) {
            return measured;
        }
        return getBestPredictedCandidate(connectionId, queryFingerprint);
    }

    @Transactional
    public List<QueryOptimizationCandidateRun> saveAll(List<QueryOptimizationCandidateRun> candidates) {
        return repository.saveAll(candidates);
    }
}
