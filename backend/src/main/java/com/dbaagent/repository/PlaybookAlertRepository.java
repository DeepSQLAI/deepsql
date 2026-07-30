package com.dbaagent.repository;

import com.dbaagent.model.PlaybookAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlaybookAlertRepository extends JpaRepository<PlaybookAlert, String> {
    List<PlaybookAlert> findByConnectionId(String connectionId);
    List<PlaybookAlert> findByConnectionIdAndAcknowledgedFalse(String connectionId);
    List<PlaybookAlert> findByPlaybookRunId(String playbookRunId);
    List<PlaybookAlert> findByConnectionIdAndSeverity(String connectionId, PlaybookAlert.Severity severity);

    @Query("SELECT pa FROM PlaybookAlert pa WHERE pa.connectionId = ?1 AND pa.createdAt >= ?2 ORDER BY pa.createdAt DESC")
    List<PlaybookAlert> findRecentAlerts(String connectionId, LocalDateTime since);

    @Query("SELECT COUNT(pa) FROM PlaybookAlert pa WHERE pa.connectionId = ?1 AND pa.acknowledged = false")
    long countUnacknowledged(String connectionId);
}
