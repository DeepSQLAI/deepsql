package com.dbaagent.repository;

import com.dbaagent.model.WorkloadAnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkloadAnalysisReportRepository extends JpaRepository<WorkloadAnalysisReport, String> {

    /** Most recent report for a connection, any status — feeds the "latest" endpoint. */
    Optional<WorkloadAnalysisReport> findFirstByConnectionIdOrderByStartedAtDesc(String connectionId);

    /** Most recent report in a given status — used to detect an already-running job. */
    Optional<WorkloadAnalysisReport> findFirstByConnectionIdAndStatusOrderByStartedAtDesc(
        String connectionId, WorkloadAnalysisReport.Status status);

    /** Recent reports for the history list (newest first). */
    List<WorkloadAnalysisReport> findTop20ByConnectionIdOrderByStartedAtDesc(String connectionId);
}
