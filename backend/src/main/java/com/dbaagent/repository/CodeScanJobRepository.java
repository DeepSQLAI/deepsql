package com.dbaagent.repository;

import com.dbaagent.model.code.CodeScanJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeScanJobRepository extends JpaRepository<CodeScanJob, String> {

    List<CodeScanJob> findBySourceIdOrderByCreatedAtDesc(String sourceId);

    Optional<CodeScanJob> findFirstBySourceIdOrderByCreatedAtDesc(String sourceId);

    List<CodeScanJob> findByConnectionIdAndStatusIn(String connectionId, List<CodeScanJob.Status> statuses);
}
