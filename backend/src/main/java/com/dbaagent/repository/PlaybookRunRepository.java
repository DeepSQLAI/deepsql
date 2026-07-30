package com.dbaagent.repository;

import com.dbaagent.model.PlaybookRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlaybookRunRepository extends JpaRepository<PlaybookRun, String> {
    List<PlaybookRun> findByConnectionId(String connectionId);
    List<PlaybookRun> findByPlaybookId(String playbookId);
    List<PlaybookRun> findByConnectionIdAndStatus(String connectionId, PlaybookRun.RunStatus status);
    List<PlaybookRun> findByConnectionIdOrderByStartedAtDesc(String connectionId);

    @Query("SELECT pr FROM PlaybookRun pr WHERE pr.connectionId = ?1 AND pr.startedAt >= ?2 ORDER BY pr.startedAt DESC")
    List<PlaybookRun> findRecentRuns(String connectionId, LocalDateTime since);

    @Query("SELECT COUNT(pr) FROM PlaybookRun pr WHERE pr.connectionId = ?1 AND pr.status = ?2")
    long countByConnectionIdAndStatus(String connectionId, PlaybookRun.RunStatus status);
}
