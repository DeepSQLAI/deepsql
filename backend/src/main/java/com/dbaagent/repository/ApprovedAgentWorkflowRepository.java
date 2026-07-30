package com.dbaagent.repository;

import com.dbaagent.model.ApprovedAgentWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovedAgentWorkflowRepository extends JpaRepository<ApprovedAgentWorkflow, String> {
    Optional<ApprovedAgentWorkflow> findByConnectionIdAndIntentAndQuestionSignature(
        String connectionId,
        String intent,
        String questionSignature
    );

    List<ApprovedAgentWorkflow> findByConnectionIdAndIntentOrderByLastApprovedAtDesc(
        String connectionId,
        String intent
    );
}
