package com.dbaagent.repository;

import com.dbaagent.model.ColumnValueCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ColumnValueCache entity.
 * Provides queries for column value management and embedding status.
 */
@Repository
public interface ColumnValueCacheRepository extends JpaRepository<ColumnValueCache, String> {

    /**
     * Find by connection, table, and column name (unique key).
     */
    Optional<ColumnValueCache> findByConnectionIdAndTableNameAndColumnName(
        String connectionId, String tableName, String columnName);

    /**
     * Find all cached columns for a connection.
     */
    List<ColumnValueCache> findByConnectionIdOrderByTableNameAscColumnNameAsc(String connectionId);

    /**
     * Find all low-cardinality columns for a connection.
     */
    List<ColumnValueCache> findByConnectionIdAndIsLowCardinalityTrue(String connectionId);

    /**
     * Find columns that haven't been embedded yet.
     */
    List<ColumnValueCache> findByConnectionIdAndEmbeddedFalse(String connectionId);

    /**
     * Find all unembedded columns (for batch embedding job).
     */
    List<ColumnValueCache> findByEmbeddedFalseOrderByCreatedAtAsc();

    /**
     * Find columns for a specific table.
     */
    List<ColumnValueCache> findByConnectionIdAndTableNameOrderByColumnNameAsc(
        String connectionId, String tableName);

    /**
     * Find low-cardinality columns for a specific table.
     */
    List<ColumnValueCache> findByConnectionIdAndTableNameAndIsLowCardinalityTrue(
        String connectionId, String tableName);

    /**
     * Find columns that need re-analysis (stale data).
     */
    @Query("SELECT c FROM ColumnValueCache c " +
           "WHERE c.connectionId = :connectionId " +
           "AND c.analyzedAt < :since " +
           "ORDER BY c.analyzedAt ASC")
    List<ColumnValueCache> findStaleColumns(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since);

    /**
     * Count low-cardinality columns for a connection.
     */
    long countByConnectionIdAndIsLowCardinalityTrue(String connectionId);

    /**
     * Count embedded columns for a connection.
     */
    long countByConnectionIdAndEmbeddedTrue(String connectionId);

    /**
     * Delete all cached values for a connection.
     */
    @Modifying
    @Query("DELETE FROM ColumnValueCache c WHERE c.connectionId = :connectionId")
    void deleteByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Delete all cached values for a table.
     */
    @Modifying
    @Query("DELETE FROM ColumnValueCache c " +
           "WHERE c.connectionId = :connectionId " +
           "AND c.tableName = :tableName")
    void deleteByConnectionIdAndTableName(
        @Param("connectionId") String connectionId,
        @Param("tableName") String tableName);

    /**
     * Mark columns as needing re-embedding.
     */
    @Modifying
    @Query("UPDATE ColumnValueCache c SET c.embedded = false " +
           "WHERE c.connectionId = :connectionId")
    void markAllAsUnembedded(@Param("connectionId") String connectionId);
}
