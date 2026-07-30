package com.dbaagent.repository;

import com.dbaagent.model.ResourceLimits;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for ResourceLimits
 * Manages database capacity configuration
 */
@Repository
public interface ResourceLimitsRepository extends JpaRepository<ResourceLimits, String> {

    /**
     * Find resource limits by connection ID
     */
    Optional<ResourceLimits> findByConnectionId(String connectionId);

    /**
     * Check if resource limits exist for connection
     */
    boolean existsByConnectionId(String connectionId);

    /**
     * Delete resource limits by connection ID
     */
    void deleteByConnectionId(String connectionId);
}
