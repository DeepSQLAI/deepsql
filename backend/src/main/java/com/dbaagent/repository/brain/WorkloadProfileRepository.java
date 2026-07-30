package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.WorkloadProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for WorkloadProfile entities.
 */
@Repository
public interface WorkloadProfileRepository extends JpaRepository<WorkloadProfile, String> {

    /**
     * Find profile by connection ID.
     */
    Optional<WorkloadProfile> findByConnectionId(String connectionId);

    /**
     * Find profiles by workload type.
     */
    List<WorkloadProfile> findByWorkloadType(WorkloadProfile.WorkloadType workloadType);

    /**
     * Find profiles with optimal config (for knowledge transfer).
     */
    @Query("SELECT p FROM WorkloadProfile p WHERE p.optimalConfig IS NOT NULL " +
           "AND p.performanceScore IS NOT NULL ORDER BY p.performanceScore DESC")
    List<WorkloadProfile> findWithOptimalConfig();

    /**
     * Find profiles similar to a given workload type.
     */
    @Query("SELECT p FROM WorkloadProfile p WHERE p.workloadType = :type " +
           "AND p.connectionId != :excludeConnId AND p.optimalConfig IS NOT NULL")
    List<WorkloadProfile> findSimilarProfiles(
        @Param("type") WorkloadProfile.WorkloadType type,
        @Param("excludeConnId") String excludeConnectionId);

    /**
     * Count profiles by workload type.
     */
    long countByWorkloadType(WorkloadProfile.WorkloadType workloadType);

    /**
     * Find profiles with high classification confidence.
     */
    @Query("SELECT p FROM WorkloadProfile p WHERE p.classificationConfidence >= :minConfidence")
    List<WorkloadProfile> findHighConfidenceProfiles(@Param("minConfidence") Double minConfidence);

    /**
     * Check if a profile exists for a connection.
     */
    boolean existsByConnectionId(String connectionId);
}
