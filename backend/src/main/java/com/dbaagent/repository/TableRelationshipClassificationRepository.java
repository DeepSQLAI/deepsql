package com.dbaagent.repository;

import com.dbaagent.model.TableRelationshipClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for TableRelationshipClassification entities.
 */
@Repository
public interface TableRelationshipClassificationRepository extends JpaRepository<TableRelationshipClassification, String> {

    /**
     * Find all relationships for a connection.
     */
    List<TableRelationshipClassification> findByConnectionIdOrderBySourceTableAsc(String connectionId);

    /**
     * Find only the latest relationship classifications for a connection.
     */
    @Query(value = """
        SELECT trc.*
        FROM table_relationship_classification trc
        WHERE trc.schema_classification_id = (
            SELECT sc.id
            FROM schema_classification sc
            WHERE sc.connection_id = :connectionId
            ORDER BY sc.classified_at DESC, sc.id DESC
            LIMIT 1
        )
        ORDER BY trc.source_table ASC, trc.target_table ASC
        """, nativeQuery = true)
    List<TableRelationshipClassification> findLatestByConnectionIdOrderBySourceTableAsc(@Param("connectionId") String connectionId);

    /**
     * Find relationships for a specific schema classification.
     */
    List<TableRelationshipClassification> findBySchemaClassificationId(String schemaClassificationId);

    /**
     * Find relationships where a table is the source (outbound).
     */
    List<TableRelationshipClassification> findByConnectionIdAndSourceTable(String connectionId, String sourceTable);

    /**
     * Find relationships where a table is the target (inbound).
     */
    List<TableRelationshipClassification> findByConnectionIdAndTargetTable(String connectionId, String targetTable);

    /**
     * Find relationships by strength level.
     */
    List<TableRelationshipClassification> findByConnectionIdAndStrength(String connectionId, String strength);

    /**
     * Find strong relationships (FK constraint exists).
     */
    @Query("SELECT r FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId AND r.strength = 'STRONG'")
    List<TableRelationshipClassification> findStrongRelationships(@Param("connectionId") String connectionId);

    /**
     * Find weak/inferred relationships that need validation.
     */
    @Query("SELECT r FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId AND r.strength IN ('WEAK', 'INFERRED') AND r.validationStatus = 'PENDING'")
    List<TableRelationshipClassification> findPendingValidation(@Param("connectionId") String connectionId);

    /**
     * Find relationships with data integrity issues (orphan records).
     */
    @Query("SELECT r FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId AND r.orphanCount > 0 ORDER BY r.orphanCount DESC")
    List<TableRelationshipClassification> findWithIntegrityIssues(@Param("connectionId") String connectionId);

    /**
     * Find high-frequency relationships (commonly used in queries).
     */
    @Query("SELECT r FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId AND r.joinFrequency > :minFrequency ORDER BY r.joinFrequency DESC")
    List<TableRelationshipClassification> findHighFrequencyRelationships(@Param("connectionId") String connectionId, @Param("minFrequency") int minFrequency);

    /**
     * Find relationships without indexes (potential performance issue).
     */
    @Query("SELECT r FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId AND r.hasIndex = false AND r.joinFrequency > 0")
    List<TableRelationshipClassification> findUnindexedRelationships(@Param("connectionId") String connectionId);

    /**
     * Find a specific relationship.
     */
    Optional<TableRelationshipClassification> findByConnectionIdAndSourceTableAndTargetTableAndSourceColumn(
        String connectionId, String sourceTable, String targetTable, String sourceColumn);

    /**
     * Count relationships by strength.
     */
    @Query("SELECT r.strength, COUNT(r) FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId GROUP BY r.strength")
    List<Object[]> countByStrength(@Param("connectionId") String connectionId);

    /**
     * Count relationships by type.
     */
    @Query("SELECT r.relationshipType, COUNT(r) FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId GROUP BY r.relationshipType")
    List<Object[]> countByType(@Param("connectionId") String connectionId);

    /**
     * Delete all relationships for a connection.
     */
    @Modifying
    @Query("DELETE FROM TableRelationshipClassification r WHERE r.connectionId = :connectionId")
    void deleteByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Delete relationships for a schema classification.
     */
    @Modifying
    void deleteBySchemaClassificationId(String schemaClassificationId);

    /**
     * Delete stale relationship classifications from older runs.
     */
    @Transactional
    @Modifying
    @Query(value = """
        DELETE FROM table_relationship_classification
        WHERE connection_id = :connectionId
          AND schema_classification_id IS NOT NULL
          AND schema_classification_id <> (
              SELECT sc.id
              FROM schema_classification sc
              WHERE sc.connection_id = :connectionId
              ORDER BY sc.classified_at DESC, sc.id DESC
              LIMIT 1
          )
        """, nativeQuery = true)
    int deleteStaleRunsForConnection(@Param("connectionId") String connectionId);

    /**
     * Update validation status.
     */
    @Modifying
    @Query("UPDATE TableRelationshipClassification r SET r.validationStatus = :status WHERE r.id = :id")
    void updateValidationStatus(@Param("id") String id, @Param("status") String status);
}
