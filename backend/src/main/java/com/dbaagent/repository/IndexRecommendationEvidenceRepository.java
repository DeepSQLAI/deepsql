package com.dbaagent.repository;

import com.dbaagent.model.IndexRecommendationEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Per-recommendation evidence rows — the queries that motivated each
 * index suggestion, with their pg_stat_statements-style call/duration
 * metrics. See V96 migration.
 */
@Repository
public interface IndexRecommendationEvidenceRepository
    extends JpaRepository<IndexRecommendationEvidence, String> {

    /** Top-K contributing queries by total time spent, for the API payload. */
    List<IndexRecommendationEvidence> findByRecommendationIdOrderByTotalExecTimeMsDesc(
        String recommendationId,
        org.springframework.data.domain.Pageable pageable
    );

    /** Lookup for upserts during a refresh cycle. */
    Optional<IndexRecommendationEvidence> findByRecommendationIdAndQueryFingerprint(
        String recommendationId,
        String queryFingerprint
    );

    long countByRecommendationId(String recommendationId);

    /** Wipe before re-populating during refresh, so stale fingerprints don't linger. */
    @Modifying
    @Query("DELETE FROM IndexRecommendationEvidence e WHERE e.recommendationId = :recommendationId")
    int deleteAllByRecommendationId(@Param("recommendationId") String recommendationId);
}
