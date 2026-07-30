package com.dbaagent.repository;

import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeKnowledgeSuggestionRepository extends JpaRepository<CodeKnowledgeSuggestion, String> {

    Page<CodeKnowledgeSuggestion> findByConnectionIdAndStatus(
        String connectionId,
        CodeKnowledgeSuggestion.Status status,
        Pageable pageable
    );

    List<CodeKnowledgeSuggestion> findByConnectionIdAndStatusOrderByConfidenceDesc(
        String connectionId,
        CodeKnowledgeSuggestion.Status status
    );

    List<CodeKnowledgeSuggestion> findByJobId(String jobId);

    long countByJobIdAndStatus(String jobId, CodeKnowledgeSuggestion.Status status);

    List<CodeKnowledgeSuggestion> findByConnectionIdAndStatusAndTargetKindAndTargetObject(
        String connectionId,
        CodeKnowledgeSuggestion.Status status,
        CodeKnowledgeSuggestion.TargetKind targetKind,
        String targetObject
    );
}
