package com.dbaagent.repository;

import com.dbaagent.model.ColumnAntiPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ColumnAntiPatternRepository extends JpaRepository<ColumnAntiPattern, String> {

    List<ColumnAntiPattern> findByConnectionIdOrderBySeverityDescDetectedAtDesc(String connectionId);

    List<ColumnAntiPattern> findByConnectionIdAndStatusOrderBySeverityDescDetectedAtDesc(
        String connectionId, ColumnAntiPattern.Status status
    );

    List<ColumnAntiPattern> findByConnectionIdAndSeverityOrderByDetectedAtDesc(
        String connectionId, ColumnAntiPattern.Severity severity
    );

    List<ColumnAntiPattern> findByConnectionIdAndTableNameAndColumnNameOrderByDetectedAtDesc(
        String connectionId, String tableName, String columnName
    );

    Optional<ColumnAntiPattern> findByConnectionIdAndTableNameAndColumnNameAndPatternType(
        String connectionId, String tableName, String columnName, String patternType
    );

    long countByConnectionIdAndStatus(String connectionId, ColumnAntiPattern.Status status);

    /**
     * Count anti-patterns by severity and connection.
     */
    long countBySeverityAndConnectionId(ColumnAntiPattern.Severity severity, String connectionId);

    void deleteByConnectionId(String connectionId);
}
