package com.dbaagent.repository;

import com.dbaagent.model.SchemaSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SchemaSnapshotRepository extends JpaRepository<SchemaSnapshot, String> {

    /**
     * Find the latest snapshot for a connection
     */
    Optional<SchemaSnapshot> findTopByConnectionIdOrderByCapturedAtDesc(String connectionId);

    /**
     * Find top 2 snapshots (for comparison)
     */
    List<SchemaSnapshot> findTop2ByConnectionIdOrderByCapturedAtDesc(String connectionId);

    /**
     * Find all snapshots for a connection ordered by capture time descending
     */
    List<SchemaSnapshot> findByConnectionIdOrderByCapturedAtDesc(String connectionId);

    /**
     * Find snapshots for a connection with pagination
     */
    List<SchemaSnapshot> findTop10ByConnectionIdOrderByCapturedAtDesc(String connectionId);

    /**
     * Find the baseline snapshot for a connection
     */
    Optional<SchemaSnapshot> findFirstByConnectionIdAndSnapshotTypeOrderByCapturedAtDesc(
            String connectionId, SchemaSnapshot.SnapshotType snapshotType);

    /**
     * Find snapshots by type
     */
    List<SchemaSnapshot> findByConnectionIdAndSnapshotTypeOrderByCapturedAtDesc(
            String connectionId, SchemaSnapshot.SnapshotType snapshotType);

    /**
     * Find snapshots within a time range
     */
    List<SchemaSnapshot> findByConnectionIdAndCapturedAtBetweenOrderByCapturedAtDesc(
            String connectionId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Check if a snapshot with the same hash already exists
     */
    boolean existsByConnectionIdAndSchemaHash(String connectionId, String schemaHash);

    /**
     * Find snapshot by hash
     */
    Optional<SchemaSnapshot> findByConnectionIdAndSchemaHash(String connectionId, String schemaHash);

    /**
     * Count snapshots for a connection
     */
    long countByConnectionId(String connectionId);

    /**
     * Delete all snapshots for a connection
     */
    @Modifying
    void deleteByConnectionId(String connectionId);

    /**
     * Delete old snapshots older than a given date
     */
    @Modifying
    @Query("DELETE FROM SchemaSnapshot s WHERE s.connectionId = :connectionId AND s.capturedAt < :before AND s.snapshotType != 'BASELINE'")
    int deleteOldSnapshots(@Param("connectionId") String connectionId, @Param("before") LocalDateTime before);
}
