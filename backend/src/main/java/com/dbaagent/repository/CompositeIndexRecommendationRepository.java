package com.dbaagent.repository;

import com.dbaagent.model.CompositeIndexRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompositeIndexRecommendationRepository extends JpaRepository<CompositeIndexRecommendation, Long> {
    List<CompositeIndexRecommendation> findByConnectionIdOrderByEstimatedBenefitScoreDesc(String connectionId);

    List<CompositeIndexRecommendation> findByConnectionIdAndStatusOrderByPriorityAsc(
        String connectionId,
        CompositeIndexRecommendation.Status status
    );

    List<CompositeIndexRecommendation> findByConnectionIdAndTableNameOrderByEstimatedBenefitScoreDesc(
        String connectionId,
        String tableName
    );

    void deleteByConnectionId(String connectionId);
}
