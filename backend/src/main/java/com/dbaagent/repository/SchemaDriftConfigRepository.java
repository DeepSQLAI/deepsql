package com.dbaagent.repository;

import com.dbaagent.model.SchemaDriftConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SchemaDriftConfigRepository extends JpaRepository<SchemaDriftConfig, String> {

    /**
     * Find config by connection ID
     */
    Optional<SchemaDriftConfig> findByConnectionId(String connectionId);

    /**
     * Check if config exists for a connection
     */
    boolean existsByConnectionId(String connectionId);

    /**
     * Find all enabled configs
     */
    List<SchemaDriftConfig> findByEnabledTrue();

    /**
     * Find configs that need to be checked (enabled and due for check)
     */
    @Query("SELECT c FROM SchemaDriftConfig c WHERE c.enabled = true AND (c.nextCheckAt IS NULL OR c.nextCheckAt <= :now)")
    List<SchemaDriftConfig> findConfigsDueForCheck(@Param("now") LocalDateTime now);

    /**
     * Delete config for a connection
     */
    void deleteByConnectionId(String connectionId);
}
