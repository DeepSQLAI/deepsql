package com.dbaagent.repository;

import com.dbaagent.model.SlowQueryCustomerDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SlowQueryCustomerDayRepository extends JpaRepository<SlowQueryCustomerDay, String> {

    /** Idempotency key for the rollup pass — one row per (query, customer, day). */
    Optional<SlowQueryCustomerDay> findByConnectionIdAndFingerprintAndCustomerIdAndDay(
        String connectionId, String fingerprint, String customerId, LocalDate day);

    /** The 30-day timeline for one (query, customer) pair — newest first. */
    List<SlowQueryCustomerDay> findByConnectionIdAndFingerprintAndCustomerIdOrderByDayDesc(
        String connectionId, String fingerprint, String customerId);

    /** Per-customer breakdown of one query on a given day. */
    List<SlowQueryCustomerDay> findByConnectionIdAndFingerprintAndDay(
        String connectionId, String fingerprint, LocalDate day);

    /** The most recent day that has any per-customer rollup for a query. */
    Optional<SlowQueryCustomerDay> findFirstByConnectionIdAndFingerprintOrderByDayDesc(
        String connectionId, String fingerprint);

    /** The previous day's row, used to compute {@code regressionFactor}. */
    Optional<SlowQueryCustomerDay> findFirstByConnectionIdAndFingerprintAndCustomerIdAndDayLessThanOrderByDayDesc(
        String connectionId, String fingerprint, String customerId, LocalDate day);

    /** Per-customer regressions on a given day, worst first. */
    @Query("""
        SELECT d FROM SlowQueryCustomerDay d
        WHERE d.connectionId = :connectionId
          AND d.day = :day
          AND d.regressionFactor IS NOT NULL
          AND d.regressionFactor >= :minFactor
        ORDER BY d.regressionFactor DESC
        """)
    List<SlowQueryCustomerDay> findRegressions(
        @Param("connectionId") String connectionId,
        @Param("day") LocalDate day,
        @Param("minFactor") double minFactor);

    /** Retention cleanup — delete rollup rows older than the cutoff date. */
    @Modifying
    @Query("DELETE FROM SlowQueryCustomerDay d WHERE d.connectionId = :connectionId AND d.day < :cutoff")
    int deleteByConnectionIdAndDayBefore(
        @Param("connectionId") String connectionId, @Param("cutoff") LocalDate cutoff);

    /** Full reset — wipe every customer-day row for a connection. */
    @Modifying
    @Query("DELETE FROM SlowQueryCustomerDay d WHERE d.connectionId = :connectionId")
    int deleteByConnectionId(@Param("connectionId") String connectionId);

    /** Per-customer aggregate across the whole retained window — for the By-Customer list. */
    interface CustomerAggregate {
        String getCustomerId();
        Long getQueryCount();
        Long getSampleCount();
        Double getTotalSlowMs();
        Double getSlowestMean();
        LocalDate getLatestDay();
    }

    @Query("""
        SELECT d.customerId           AS customerId,
               COUNT(DISTINCT d.fingerprint) AS queryCount,
               SUM(d.sampleCount)     AS sampleCount,
               SUM(d.totalExecMs)     AS totalSlowMs,
               MAX(d.meanExecMs)      AS slowestMean,
               MAX(d.day)             AS latestDay
        FROM SlowQueryCustomerDay d
        WHERE d.connectionId = :connectionId
        GROUP BY d.customerId
        """)
    List<CustomerAggregate> aggregateByCustomer(@Param("connectionId") String connectionId);

    /** Per-query aggregate for one customer — for the customer's query list. */
    interface CustomerQueryAggregate {
        String getFingerprint();
        Long getSampleCount();
        Double getTotalExecMs();
        Double getMaxExecMs();
        LocalDate getLatestDay();
    }

    @Query("""
        SELECT d.fingerprint          AS fingerprint,
               SUM(d.sampleCount)     AS sampleCount,
               SUM(d.totalExecMs)     AS totalExecMs,
               MAX(d.maxExecMs)       AS maxExecMs,
               MAX(d.day)             AS latestDay
        FROM SlowQueryCustomerDay d
        WHERE d.connectionId = :connectionId AND d.customerId = :customerId
        GROUP BY d.fingerprint
        """)
    List<CustomerQueryAggregate> aggregateQueriesForCustomer(
        @Param("connectionId") String connectionId,
        @Param("customerId") String customerId);
}
