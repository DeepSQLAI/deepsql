package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.ColumnStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ColumnStatistics entities.
 */
@Repository
public interface ColumnStatisticsRepository extends JpaRepository<ColumnStatistics, String> {

    /**
     * Find statistics for a specific column.
     */
    Optional<ColumnStatistics> findByConnectionIdAndTableNameAndColumnName(
        String connectionId, String tableName, String columnName);

    /**
     * Find all statistics for a table.
     */
    List<ColumnStatistics> findByConnectionIdAndTableName(String connectionId, String tableName);

    /**
     * Find all statistics for a connection.
     */
    List<ColumnStatistics> findByConnectionId(String connectionId);

    /**
     * Count statistics for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Find statistics that need refresh (older than threshold).
     */
    @Query("SELECT c FROM ColumnStatistics c WHERE c.connectionId = :connId " +
           "AND c.collectedAt < :threshold")
    List<ColumnStatistics> findStaleStatistics(
        @Param("connId") String connectionId,
        @Param("threshold") LocalDateTime threshold);

    /**
     * Find columns with high distinct count (for index candidates).
     */
    @Query("SELECT c FROM ColumnStatistics c WHERE c.connectionId = :connId " +
           "AND c.distinctCount > :minDistinct ORDER BY c.distinctCount DESC")
    List<ColumnStatistics> findHighCardinalityColumns(
        @Param("connId") String connectionId,
        @Param("minDistinct") Long minDistinct);

    /**
     * Find columns with skewed distribution (high MCV frequency).
     */
    @Query("SELECT c FROM ColumnStatistics c WHERE c.connectionId = :connId " +
           "AND c.mcvFrequencies IS NOT NULL")
    List<ColumnStatistics> findColumnsWithMCV(@Param("connId") String connectionId);

    /**
     * Delete statistics for a table (when table is dropped or renamed).
     */
    @Modifying
    void deleteByConnectionIdAndTableName(String connectionId, String tableName);

    /**
     * Find columns with histogram data.
     */
    @Query("SELECT c FROM ColumnStatistics c WHERE c.connectionId = :connId " +
           "AND c.histogramBounds IS NOT NULL")
    List<ColumnStatistics> findColumnsWithHistogram(@Param("connId") String connectionId);

    /**
     * Find numeric columns for range estimation.
     */
    @Query("SELECT c FROM ColumnStatistics c WHERE c.connectionId = :connId " +
           "AND c.minValue IS NOT NULL AND c.maxValue IS NOT NULL")
    List<ColumnStatistics> findNumericColumns(@Param("connId") String connectionId);
}
