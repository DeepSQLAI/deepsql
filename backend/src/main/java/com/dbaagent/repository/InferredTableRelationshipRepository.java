package com.dbaagent.repository;

import com.dbaagent.model.InferredTableRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository for InferredTableRelationship entity.
 * Provides methods for querying inferred table relationships.
 */
@Repository
public interface InferredTableRelationshipRepository extends JpaRepository<InferredTableRelationship, String> {

    /**
     * Find all relationships for a connection.
     */
    List<InferredTableRelationship> findByConnectionIdOrderByConfidenceScoreDesc(String connectionId);

    /**
     * Find all relationships for a connection, ordered by join count.
     */
    List<InferredTableRelationship> findByConnectionIdOrderByJoinCountDesc(String connectionId);

    /**
     * Find relationships where a table is the source (FK side).
     */
    List<InferredTableRelationship> findByConnectionIdAndSourceTable(String connectionId, String sourceTable);

    /**
     * Find relationships where a table is the target (PK side).
     */
    List<InferredTableRelationship> findByConnectionIdAndTargetTable(String connectionId, String targetTable);

    /**
     * Find high-confidence relationships (for use in schema classification).
     */
    @Query("SELECT r FROM InferredTableRelationship r " +
           "WHERE r.connectionId = :connectionId " +
           "AND r.confidenceScore >= :minConfidence " +
           "AND r.status <> 'REJECTED' " +
           "ORDER BY r.confidenceScore DESC")
    List<InferredTableRelationship> findHighConfidenceRelationships(
        @Param("connectionId") String connectionId,
        @Param("minConfidence") BigDecimal minConfidence
    );

    /**
     * Find a specific relationship by source and target.
     */
    Optional<InferredTableRelationship> findByConnectionIdAndSourceTableAndSourceColumnAndTargetTableAndTargetColumn(
        String connectionId,
        String sourceTable,
        String sourceColumn,
        String targetTable,
        String targetColumn
    );

    /**
     * Find all relationships involving a specific table (as source or target).
     */
    @Query("SELECT r FROM InferredTableRelationship r " +
           "WHERE r.connectionId = :connectionId " +
           "AND (r.sourceTable = :tableName OR r.targetTable = :tableName) " +
           "ORDER BY r.confidenceScore DESC")
    List<InferredTableRelationship> findByConnectionIdAndTable(
        @Param("connectionId") String connectionId,
        @Param("tableName") String tableName
    );

    /**
     * Find relationships by status.
     */
    List<InferredTableRelationship> findByConnectionIdAndStatus(String connectionId, String status);

    /**
     * Find validated relationships only.
     */
    @Query("SELECT r FROM InferredTableRelationship r " +
           "WHERE r.connectionId = :connectionId " +
           "AND r.status = 'VALIDATED' " +
           "ORDER BY r.sourceTable, r.targetTable")
    List<InferredTableRelationship> findValidatedRelationships(@Param("connectionId") String connectionId);

    /**
     * Count relationships by status for a connection.
     */
    long countByConnectionIdAndStatus(String connectionId, String status);

    /**
     * Count total relationships for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Delete all relationships for a connection.
     */
    void deleteByConnectionId(String connectionId);

    /**
     * Find self-joins for a connection.
     */
    @Query("SELECT r FROM InferredTableRelationship r " +
           "WHERE r.connectionId = :connectionId " +
           "AND r.isSelfJoin = true " +
           "ORDER BY r.confidenceScore DESC")
    List<InferredTableRelationship> findSelfJoins(@Param("connectionId") String connectionId);
}
