package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.BrainScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrainScoreRepository extends JpaRepository<BrainScore, String> {

    /**
     * Get the latest Brain Score for a connection.
     */
    @Query("SELECT bs FROM BrainScore bs WHERE bs.connectionId = :connectionId " +
           "ORDER BY bs.calculatedAt DESC LIMIT 1")
    Optional<BrainScore> findLatestByConnectionId(String connectionId);

    /**
     * Get Brain Score history for a connection (for trend analysis).
     */
    List<BrainScore> findByConnectionIdOrderByCalculatedAtDesc(String connectionId);

    /**
     * Get Brain Score history with limit.
     */
    @Query("SELECT bs FROM BrainScore bs WHERE bs.connectionId = :connectionId " +
           "ORDER BY bs.calculatedAt DESC LIMIT :limit")
    List<BrainScore> findTopByConnectionIdOrderByCalculatedAtDesc(String connectionId, int limit);
}
