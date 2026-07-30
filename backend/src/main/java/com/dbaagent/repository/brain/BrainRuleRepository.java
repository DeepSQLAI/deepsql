package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.BrainRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrainRuleRepository extends JpaRepository<BrainRule, String> {

    /**
     * Find all active rules for a connection.
     */
    List<BrainRule> findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc(String connectionId);

    /**
     * Find all rules for a connection (including inactive).
     */
    List<BrainRule> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Find rules by type for a connection.
     */
    List<BrainRule> findByConnectionIdAndRuleTypeAndIsActiveTrueOrderByCreatedAtDesc(
        String connectionId, String ruleType);

    /**
     * Find rules for a specific table.
     */
    List<BrainRule> findByConnectionIdAndTableNameAndIsActiveTrueOrderByCreatedAtDesc(
        String connectionId, String tableName);

    /**
     * Find rules for a specific column.
     */
    List<BrainRule> findByConnectionIdAndTableNameAndColumnNameAndIsActiveTrueOrderByCreatedAtDesc(
        String connectionId, String tableName, String columnName);

    /**
     * Count active rules for a connection.
     */
    long countByConnectionIdAndIsActiveTrue(String connectionId);
}
