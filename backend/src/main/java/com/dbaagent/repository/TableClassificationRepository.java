package com.dbaagent.repository;

import com.dbaagent.model.TableClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TableClassificationRepository extends JpaRepository<TableClassification, String> {

    /**
     * Find all table classifications for a schema classification.
     */
    List<TableClassification> findBySchemaClassificationIdOrderByTableNameAsc(String schemaClassificationId);

    /**
     * Find all table classifications for a schema classification (without ordering).
     */
    List<TableClassification> findBySchemaClassificationId(String schemaClassificationId);

    /**
     * Find table classifications by schema ID and role.
     */
    List<TableClassification> findBySchemaClassificationIdAndTableRole(String schemaClassificationId, String tableRole);

    /**
     * Find all table classifications for a connection.
     */
    List<TableClassification> findByConnectionIdOrderByTableNameAsc(String connectionId);

    /**
     * Find only the latest table classifications for a connection.
     */
    @Query(value = """
        SELECT tc.*
        FROM table_classification tc
        WHERE tc.schema_classification_id = (
            SELECT sc.id
            FROM schema_classification sc
            WHERE sc.connection_id = :connectionId
            ORDER BY sc.classified_at DESC, sc.id DESC
            LIMIT 1
        )
        ORDER BY tc.table_name ASC
        """, nativeQuery = true)
    List<TableClassification> findLatestByConnectionIdOrderByTableNameAsc(@Param("connectionId") String connectionId);

    /**
     * Find tables by role.
     */
    List<TableClassification> findByConnectionIdAndTableRole(String connectionId, String tableRole);

    /**
     * Find latest tables by role for a connection.
     */
    @Query(value = """
        SELECT tc.*
        FROM table_classification tc
        WHERE tc.schema_classification_id = (
            SELECT sc.id
            FROM schema_classification sc
            WHERE sc.connection_id = :connectionId
            ORDER BY sc.classified_at DESC, sc.id DESC
            LIMIT 1
        )
          AND UPPER(tc.table_role) = UPPER(:tableRole)
        ORDER BY COALESCE(tc.row_count, 0) DESC, tc.table_name ASC
        """, nativeQuery = true)
    List<TableClassification> findLatestByConnectionIdAndTableRole(
        @Param("connectionId") String connectionId,
        @Param("tableRole") String tableRole);

    /**
     * Find fact tables.
     */
    @Query("SELECT tc FROM TableClassification tc WHERE tc.connectionId = :connectionId " +
           "AND tc.tableRole = 'FACT' ORDER BY tc.rowCount DESC")
    List<TableClassification> findFactTablesByConnectionId(String connectionId);

    /**
     * Delete stale table classifications from older schema-classification runs.
     */
    @Transactional
    @Modifying
    @Query(value = """
        DELETE FROM table_classification
        WHERE connection_id = :connectionId
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
     * Find dimension tables.
     */
    @Query("SELECT tc FROM TableClassification tc WHERE tc.connectionId = :connectionId " +
           "AND tc.tableRole = 'DIMENSION' ORDER BY tc.tableName ASC")
    List<TableClassification> findDimensionTablesByConnectionId(String connectionId);

    /**
     * Find orphaned tables.
     */
    @Query("SELECT tc FROM TableClassification tc WHERE tc.connectionId = :connectionId " +
           "AND tc.tableRole = 'ORPHANED' ORDER BY tc.tableName ASC")
    List<TableClassification> findOrphanedTablesByConnectionId(String connectionId);

    /**
     * Find a specific table classification.
     */
    Optional<TableClassification> findBySchemaClassificationIdAndTableName(
        String schemaClassificationId, String tableName);

    /**
     * Count tables by role.
     */
    long countByConnectionIdAndTableRole(String connectionId, String tableRole);
}
