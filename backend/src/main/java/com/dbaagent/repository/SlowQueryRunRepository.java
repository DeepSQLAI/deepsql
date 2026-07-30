package com.dbaagent.repository;

import com.dbaagent.model.SlowQueryRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SlowQueryRunRepository extends JpaRepository<SlowQueryRun, String> {

    /** The 30-day timeline for one query — newest first. */
    List<SlowQueryRun> findByConnectionIdAndFingerprintOrderByAnalyzedOnDesc(
        String connectionId, String fingerprint);

    /** The single most recent run for one query — used to compute the next delta. */
    Optional<SlowQueryRun> findFirstByConnectionIdAndFingerprintOrderByAnalyzedOnDesc(
        String connectionId, String fingerprint);

    /**
     * The most recent run STRICTLY BEFORE a given day. The daily job uses this
     * (not the plain "most recent") so that re-running today's analysis still
     * diffs against yesterday, not against the half-written row for today.
     */
    Optional<SlowQueryRun> findFirstByConnectionIdAndFingerprintAndAnalyzedOnLessThanOrderByAnalyzedOnDesc(
        String connectionId, String fingerprint, LocalDate analyzedOn);

    /** The exact row for one (query, day) — used to make the daily job idempotent. */
    Optional<SlowQueryRun> findByConnectionIdAndFingerprintAndAnalyzedOn(
        String connectionId, String fingerprint, LocalDate analyzedOn);

    /** Every query analyzed on a given day for a connection. */
    List<SlowQueryRun> findByConnectionIdAndAnalyzedOn(String connectionId, LocalDate analyzedOn);

    /** The most recent run on a connection — used to find the latest analyzed day. */
    Optional<SlowQueryRun> findTopByConnectionIdOrderByAnalyzedOnDesc(String connectionId);

    /** Queries that regressed on a given day, worst first. */
    @Query("""
        SELECT r FROM SlowQueryRun r
        WHERE r.connectionId = :connectionId
          AND r.analyzedOn = :analyzedOn
          AND r.regressionFactor IS NOT NULL
          AND r.regressionFactor >= :minFactor
        ORDER BY r.regressionFactor DESC
        """)
    List<SlowQueryRun> findRegressions(
        @Param("connectionId") String connectionId,
        @Param("analyzedOn") LocalDate analyzedOn,
        @Param("minFactor") double minFactor);

    // ── Workload Analysis candidate selection ────────────────────────────────
    // The holistic workload job picks its candidate query set from three angles
    // on the latest analyzed day; the union (deduped by fingerprint) is what it
    // runs plan analysis + rewrite + pre-agg detection against. Each query takes
    // a Pageable so the caller caps N (e.g. top 20).

    /** Most-affected: highest total DB time this period (Σ exec time delta). */
    @Query("""
        SELECT r FROM SlowQueryRun r
        WHERE r.connectionId = :connectionId
          AND r.analyzedOn = :analyzedOn
          AND r.totalExecMsDelta IS NOT NULL
        ORDER BY r.totalExecMsDelta DESC
        """)
    List<SlowQueryRun> findMostAffected(
        @Param("connectionId") String connectionId,
        @Param("analyzedOn") LocalDate analyzedOn,
        Pageable pageable);

    /** High-volume: most calls this period (Σ calls delta). */
    @Query("""
        SELECT r FROM SlowQueryRun r
        WHERE r.connectionId = :connectionId
          AND r.analyzedOn = :analyzedOn
          AND r.callsDelta IS NOT NULL
        ORDER BY r.callsDelta DESC
        """)
    List<SlowQueryRun> findHighVolume(
        @Param("connectionId") String connectionId,
        @Param("analyzedOn") LocalDate analyzedOn,
        Pageable pageable);

    /** Retention cleanup — delete runs older than the cutoff date. */
    @Modifying
    @Query("DELETE FROM SlowQueryRun r WHERE r.connectionId = :connectionId AND r.analyzedOn < :cutoff")
    int deleteByConnectionIdAndAnalyzedOnBefore(
        @Param("connectionId") String connectionId, @Param("cutoff") LocalDate cutoff);

    /** Full reset — wipe every run row for a connection. */
    @Modifying
    @Query("DELETE FROM SlowQueryRun r WHERE r.connectionId = :connectionId")
    int deleteByConnectionId(@Param("connectionId") String connectionId);
}
