package com.dbaagent.repository;

import com.dbaagent.model.QueryOptimizationCandidateRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QueryOptimizationCandidateRunRepository extends JpaRepository<QueryOptimizationCandidateRun, String> {
    Optional<QueryOptimizationCandidateRun> findByConnectionIdAndQueryFingerprintAndCandidateId(
        String connectionId, String queryFingerprint, String candidateId);

    List<QueryOptimizationCandidateRun> findByConnectionIdAndQueryFingerprint(
        String connectionId, String queryFingerprint);

    List<QueryOptimizationCandidateRun> findByConnectionIdAndQueryFingerprintAndStatus(
        String connectionId, String queryFingerprint, String status);
}
