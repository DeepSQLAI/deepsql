package com.dbaagent.repository;

import com.dbaagent.model.ColumnProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ColumnProfileRepository extends JpaRepository<ColumnProfile, String> {
    List<ColumnProfile> findByConnectionId(String connectionId);

    List<ColumnProfile> findByConnectionIdAndTableName(String connectionId, String tableName);

    Optional<ColumnProfile> findByConnectionIdAndTableNameAndColumnName(
        String connectionId,
        String tableName,
        String columnName
    );

    long countByConnectionId(String connectionId);

    @Query("""
        SELECT MAX(p.profiledAt)
        FROM ColumnProfile p
        WHERE p.connectionId = :connectionId
        """)
    LocalDateTime findLatestProfiledAt(@Param("connectionId") String connectionId);
}
