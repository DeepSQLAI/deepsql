package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.CostCalibration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for CostCalibration entities.
 */
@Repository
public interface CostCalibrationRepository extends JpaRepository<CostCalibration, String> {

    /**
     * Find calibration for a connection.
     */
    Optional<CostCalibration> findByConnectionId(String connectionId);

    /**
     * Check if calibration exists for a connection.
     */
    boolean existsByConnectionId(String connectionId);

    /**
     * Delete calibration for a connection.
     */
    void deleteByConnectionId(String connectionId);
}
