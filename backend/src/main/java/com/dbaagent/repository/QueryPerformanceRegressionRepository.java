package com.dbaagent.repository;

import com.dbaagent.model.QueryPerformanceRegression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface QueryPerformanceRegressionRepository extends JpaRepository<QueryPerformanceRegression, Long> {

    List<QueryPerformanceRegression> findByConnectionIdOrderByDetectedAtDesc(String connectionId);

    List<QueryPerformanceRegression> findByConnectionIdAndResolvedFalseOrderByDetectedAtDesc(String connectionId);

    List<QueryPerformanceRegression> findByConnectionIdAndAcknowledgedFalseAndResolvedFalseOrderByDetectedAtDesc(
            String connectionId
    );

    @Query("SELECT COUNT(r) FROM QueryPerformanceRegression r WHERE r.connectionId = :connectionId " +
           "AND r.resolved = false")
    long countActiveRegressionsByConnectionId(@Param("connectionId") String connectionId);

    @Query("SELECT COUNT(r) FROM QueryPerformanceRegression r WHERE r.connectionId = :connectionId " +
           "AND r.severity = :severity AND r.resolved = false")
    long countActiveRegressionsBySeverity(
            @Param("connectionId") String connectionId,
            @Param("severity") QueryPerformanceRegression.Severity severity
    );

    @Query("SELECT r FROM QueryPerformanceRegression r WHERE r.connectionId = :connectionId " +
           "AND r.queryHash = :queryHash AND r.resolved = false")
    List<QueryPerformanceRegression> findActiveRegressionsByQuery(
            @Param("connectionId") String connectionId,
            @Param("queryHash") String queryHash
    );

    void deleteByDetectedAtBefore(LocalDateTime timestamp);

    void deleteByConnectionId(String connectionId);
}
