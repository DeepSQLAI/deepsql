package com.dbaagent.repository;

import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CodeKnowledgeSuggestionRepository extends JpaRepository<CodeKnowledgeSuggestion, String> {

    /**
     * Row-locking load used by approve/reject. Without it two concurrent bulk
     * decides both read the same suggestion as PENDING and both materialize a
     * {@code schema_documentation} row — the duplicate-row bug that
     * {@code V116__dedupe_schema_documentation.sql} had to clean up. The second
     * caller now blocks, then sees APPROVED and returns early.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CodeKnowledgeSuggestion s WHERE s.id = :id")
    Optional<CodeKnowledgeSuggestion> findByIdForUpdate(@Param("id") String id);

    /**
     * Repoints approvals at the surviving row when duplicate schema_documentation
     * rows are collapsed. {@code applied_doc_id} is a loose reference, not an FK,
     * so deleting a duplicate would otherwise leave a suggestion pointing at a row
     * that no longer exists — silently, since nothing enforces it.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE CodeKnowledgeSuggestion s SET s.appliedDocId = :keepId "
         + "WHERE s.appliedDocId IN :staleIds")
    int repointAppliedDocId(@Param("keepId") String keepId,
                            @Param("staleIds") Collection<String> staleIds);

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
