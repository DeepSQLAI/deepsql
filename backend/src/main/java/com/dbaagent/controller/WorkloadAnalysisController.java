package com.dbaagent.controller;

import com.dbaagent.model.WorkloadAnalysisReport;
import com.dbaagent.repository.WorkloadAnalysisReportRepository;
import com.dbaagent.service.WorkloadAnalysisService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On-demand holistic Workload Analysis.
 *
 * <p>Trigger is manual (UI button); the run is a long-running background job
 * (virtual thread) that composes index recs, rewrites, pre-aggregation recs,
 * and index-health cleanup into one {@link WorkloadAnalysisReport}. Clients
 * start a run, then poll {@code /status} (or {@code /latest}) for progress.
 */
@RestController
@RequestMapping("/workload-analysis")
@RequiredArgsConstructor
@Slf4j
public class WorkloadAnalysisController {

    // Reads use canReadContent; only `run` requires canManageContent. The reads were all
    // gated on manage, which is the write tier — EffectiveConnectionAccess's own comment
    // lists slow-query analytics under read. Latent today because every grant resolves to
    // FULL_CONTENT (so both predicates are true), but it would deny the whole Workload tab
    // to a read-only grant the moment one is reintroduced.

    private final WorkloadAnalysisService workloadAnalysisService;
    private final WorkloadAnalysisReportRepository reportRepository;
    private final AccessControlService accessControlService;

    /**
     * Kick off a workload analysis. Idempotent under concurrency: if one is
     * already PENDING/RUNNING for this connection, returns that one instead of
     * starting a second.
     */
    @PostMapping("/{connectionId}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);

        var active = workloadAnalysisService.findActive(connectionId);
        if (active.isPresent()) {
            return ResponseEntity.ok(Map.of(
                "reportId", active.get().getId(),
                "status", active.get().getStatus().name(),
                "alreadyRunning", true));
        }

        WorkloadAnalysisReport report = workloadAnalysisService.createPendingReport(
            connectionId, accessControlService.getCurrentUsername());
        String reportId = report.getId();

        Thread.ofVirtual().name("workload-analysis-" + reportId.substring(0, 8).toLowerCase(Locale.ROOT))
            .start(() -> workloadAnalysisService.runAnalysis(reportId));

        return ResponseEntity.ok(Map.of(
            "reportId", reportId,
            "status", report.getStatus().name(),
            "alreadyRunning", false));
    }

    /** Lightweight poll payload — status + progress without the full report blob. */
    @GetMapping("/{connectionId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return reportRepository.findFirstByConnectionIdOrderByStartedAtDesc(connectionId)
            .map(r -> ResponseEntity.ok(Map.<String, Object>of(
                "reportId", r.getId(),
                "status", r.getStatus().name(),
                "progress", r.getProgress(),
                "currentStep", r.getCurrentStep() == null ? "" : r.getCurrentStep(),
                "candidateCount", r.getCandidateCount(),
                "indexRecCount", r.getIndexRecCount(),
                "rewriteRecCount", r.getRewriteRecCount(),
                "preAggRecCount", r.getPreAggRecCount())))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The full latest report (with the composed sections). 204 if none yet. */
    @GetMapping("/{connectionId}/latest")
    public ResponseEntity<WorkloadAnalysisReport> latest(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return reportRepository.findFirstByConnectionIdOrderByStartedAtDesc(connectionId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** A specific report by id. */
    @GetMapping("/report/{reportId}")
    public ResponseEntity<WorkloadAnalysisReport> getReport(@PathVariable String reportId) {
        return reportRepository.findById(reportId)
            .map(r -> {
                accessControlService.assertCanReadConnectionContent(r.getConnectionId());
                return ResponseEntity.ok(r);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Recent report history (newest first), metadata only via the entity. */
    @GetMapping("/{connectionId}/history")
    public ResponseEntity<List<WorkloadAnalysisReport>> history(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(reportRepository.findTop20ByConnectionIdOrderByStartedAtDesc(connectionId));
    }
}
