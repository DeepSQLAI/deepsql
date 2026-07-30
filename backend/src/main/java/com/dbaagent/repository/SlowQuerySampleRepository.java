package com.dbaagent.repository;

import com.dbaagent.model.SlowQuerySample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SlowQuerySampleRepository extends JpaRepository<SlowQuerySample, String> {

    /** Samples for one query in a time window — used to build the per-customer rollup. */
    List<SlowQuerySample> findByConnectionIdAndFingerprintAndCapturedAtBetween(
        String connectionId, String fingerprint, LocalDateTime from, LocalDateTime to);

    /** Samples captured in a window for a connection — drives the daily rollup pass. */
    List<SlowQuerySample> findByConnectionIdAndCapturedAtBetween(
        String connectionId, LocalDateTime from, LocalDateTime to);

    /** Recent literal-bearing examples for one customer + query. */
    List<SlowQuerySample> findTop20ByConnectionIdAndFingerprintAndCustomerIdOrderByExecMsDesc(
        String connectionId, String fingerprint, String customerId);

    /** The slowest literal-bearing examples for one query — copyable real SQL. */
    List<SlowQuerySample> findTop10ByConnectionIdAndFingerprintOrderByExecMsDesc(
        String connectionId, String fingerprint);

    /** Retention cleanup — delete samples older than the cutoff timestamp. */
    @Modifying
    @Query("DELETE FROM SlowQuerySample s WHERE s.connectionId = :connectionId AND s.capturedAt < :cutoff")
    int deleteByConnectionIdAndCapturedAtBefore(
        @Param("connectionId") String connectionId, @Param("cutoff") LocalDateTime cutoff);

    /** Full reset — wipe every sample row for a connection. */
    @Modifying
    @Query("DELETE FROM SlowQuerySample s WHERE s.connectionId = :connectionId")
    int deleteByConnectionId(@Param("connectionId") String connectionId);
}
