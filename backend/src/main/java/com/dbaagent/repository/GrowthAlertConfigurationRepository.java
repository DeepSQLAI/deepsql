package com.dbaagent.repository;

import com.dbaagent.model.GrowthAlertConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GrowthAlertConfigurationRepository extends JpaRepository<GrowthAlertConfiguration, String> {

    /**
     * Find configuration for a specific table
     */
    Optional<GrowthAlertConfiguration> findByConnectionIdAndTableName(String connectionId, String tableName);

    /**
     * Find global configuration for a connection (where tableName is NULL)
     */
    Optional<GrowthAlertConfiguration> findByConnectionIdAndTableNameIsNull(String connectionId);

    /**
     * Find all configurations for a connection (both global and table-specific)
     */
    List<GrowthAlertConfiguration> findByConnectionId(String connectionId);

    /**
     * Find enabled configurations only
     */
    List<GrowthAlertConfiguration> findByConnectionIdAndIsEnabled(String connectionId, Boolean isEnabled);

    /**
     * Get effective configuration for a table
     * Tries to find table-specific config first, falls back to global config
     */
    @Query("SELECT c FROM GrowthAlertConfiguration c WHERE c.connectionId = :connectionId " +
           "AND (c.tableName = :tableName OR (c.tableName IS NULL AND NOT EXISTS " +
           "(SELECT c2 FROM GrowthAlertConfiguration c2 WHERE c2.connectionId = :connectionId AND c2.tableName = :tableName))) " +
           "ORDER BY CASE WHEN c.tableName IS NULL THEN 1 ELSE 0 END")
    Optional<GrowthAlertConfiguration> findEffectiveConfiguration(
        @Param("connectionId") String connectionId,
        @Param("tableName") String tableName
    );

    /**
     * Check if a configuration exists for a table
     */
    boolean existsByConnectionIdAndTableName(String connectionId, String tableName);

    /**
     * Count configurations for a connection
     */
    long countByConnectionId(String connectionId);
}
