package com.dbaagent.repository;

import com.dbaagent.model.ColumnUsagePattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ColumnUsagePatternRepository extends JpaRepository<ColumnUsagePattern, String> {

    List<ColumnUsagePattern> findByConnectionIdAndTableNameAndColumnNameOrderByCreatedAtDesc(
        String connectionId, String tableName, String columnName
    );

    List<ColumnUsagePattern> findByConnectionIdAndTableNameAndColumnNameAndUsageType(
        String connectionId, String tableName, String columnName, String usageType
    );

    List<ColumnUsagePattern> findByConnectionIdAndUsageType(String connectionId, String usageType);

    List<ColumnUsagePattern> findByConnectionIdAndQuerySource(String connectionId, String querySource);

    Optional<ColumnUsagePattern> findByConnectionIdAndTableNameAndColumnNameAndQueryHashAndUsageType(
        String connectionId, String tableName, String columnName, String queryHash, String usageType
    );

    void deleteByConnectionId(String connectionId);
}
