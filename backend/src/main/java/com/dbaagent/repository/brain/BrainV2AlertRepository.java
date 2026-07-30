package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.BrainV2Alert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for BrainV2Alert entities.
 */
@Repository
public interface BrainV2AlertRepository extends JpaRepository<BrainV2Alert, String> {

    /**
     * Find active alerts for a connection.
     */
    List<BrainV2Alert> findByConnectionIdAndStatusOrderByCreatedAtDesc(
        String connectionId, BrainV2Alert.Status status);

    /**
     * Find alerts by type.
     */
    List<BrainV2Alert> findByConnectionIdAndAlertTypeOrderByCreatedAtDesc(
        String connectionId, BrainV2Alert.AlertType alertType);

    /**
     * Find recent alerts for a connection.
     */
    List<BrainV2Alert> findByConnectionIdOrderByCreatedAtDesc(
        String connectionId, Pageable pageable);

    /**
     * Find high severity active alerts.
     */
    @Query("SELECT a FROM BrainV2Alert a WHERE a.connectionId = :connId " +
           "AND a.status = 'ACTIVE' AND a.severity IN ('HIGH', 'CRITICAL') " +
           "ORDER BY a.createdAt DESC")
    List<BrainV2Alert> findHighSeverityAlerts(@Param("connId") String connectionId);

    /**
     * Count active alerts by severity.
     */
    long countByConnectionIdAndStatusAndSeverity(
        String connectionId, BrainV2Alert.Status status, BrainV2Alert.Severity severity);

    /**
     * Find all active alerts across all connections.
     */
    @Query("SELECT a FROM BrainV2Alert a WHERE a.status = 'ACTIVE' " +
           "ORDER BY a.severity DESC, a.createdAt DESC")
    List<BrainV2Alert> findAllActiveAlerts(Pageable pageable);

    /**
     * Delete old resolved alerts.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM BrainV2Alert a WHERE a.status = 'RESOLVED' " +
           "AND a.resolvedAt < :cutoff")
    void deleteOldResolvedAlerts(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count alerts by type for a connection.
     */
    long countByConnectionIdAndAlertType(String connectionId, BrainV2Alert.AlertType alertType);

    /**
     * Find recent alerts of a specific type.
     */
    @Query("SELECT a FROM BrainV2Alert a WHERE a.connectionId = :connId " +
           "AND a.alertType = :type AND a.createdAt > :since ORDER BY a.createdAt DESC")
    List<BrainV2Alert> findRecentAlertsByType(
        @Param("connId") String connectionId,
        @Param("type") BrainV2Alert.AlertType type,
        @Param("since") LocalDateTime since);
}
