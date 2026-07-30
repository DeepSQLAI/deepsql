package com.dbaagent.repository;

import com.dbaagent.model.KeyColumnAnalysis;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface KeyColumnAnalysisRepository extends JpaRepository<KeyColumnAnalysis, String> {

    List<KeyColumnAnalysis> findByConnectionIdOrderByImportanceScoreDesc(String connectionId);

    @Query("SELECT k FROM KeyColumnAnalysis k WHERE k.connectionId = :connectionId " +
           "AND k.importanceScore >= :minScore ORDER BY k.importanceScore DESC")
    List<KeyColumnAnalysis> findTopKeyColumns(
        @Param("connectionId") String connectionId,
        @Param("minScore") BigDecimal minScore,
        Pageable pageable
    );

    List<KeyColumnAnalysis> findByConnectionIdAndTableNameOrderByImportanceScoreDesc(
        String connectionId, String tableName
    );

    List<KeyColumnAnalysis> findByConnectionIdAndHasAntiPatternsTrueOrderByImportanceScoreDesc(
        String connectionId
    );

    Optional<KeyColumnAnalysis> findByConnectionIdAndTableNameAndColumnName(
        String connectionId, String tableName, String columnName
    );

    void deleteByConnectionId(String connectionId);

    /**
     * Count distinct tables analyzed for a connection.
     */
    @Query("SELECT COUNT(DISTINCT k.tableName) FROM KeyColumnAnalysis k WHERE k.connectionId = :connectionId")
    long countDistinctTablesByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Count total columns analyzed for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Find columns with low cardinality (for column value caching).
     * Used by ColumnValueCollectionService.
     */
    List<KeyColumnAnalysis> findByConnectionIdAndDistinctCountLessThan(
        String connectionId, Long maxDistinctCount
    );

    /**
     * Find columns with known distinct count for a specific table.
     */
    List<KeyColumnAnalysis> findByConnectionIdAndTableNameAndDistinctCountIsNotNull(
        String connectionId, String tableName
    );
}
