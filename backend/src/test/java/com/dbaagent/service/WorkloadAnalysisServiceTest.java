package com.dbaagent.service;

import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.SlowQueryRun;
import com.dbaagent.model.WorkloadAnalysisReport;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.SlowQueryRunRepository;
import com.dbaagent.repository.WorkloadAnalysisReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkloadAnalysisServiceTest {

    @Mock private WorkloadAnalysisReportRepository reportRepository;
    @Mock private IndexRecommendationService indexRecommendationService;
    @Mock private QueryOptimizationService queryOptimizationService;
    @Mock private PerformanceMonitoringService performanceMonitoringService;
    @Mock private SlowQueryRunRepository slowQueryRunRepository;
    @Mock private QueryFingerprintRepository fingerprintRepository;
    @Mock private PreAggregationAnalyzer preAggregationAnalyzer;

    private WorkloadAnalysisService service;

    private static SlowQueryRun run(String fp, double totalMs, long calls) {
        SlowQueryRun r = new SlowQueryRun();
        r.setFingerprint(fp);
        r.setAnalyzedOn(LocalDate.of(2026, 6, 4));
        r.setTotalExecMsDelta(totalMs);
        r.setCallsDelta(calls);
        r.setMeanExecMs(totalMs / Math.max(1, calls));
        r.setMaxExecMs(totalMs);
        return r;
    }

    @BeforeEach
    void setUp() {
        service = new WorkloadAnalysisService(
            reportRepository, indexRecommendationService, queryOptimizationService,
            performanceMonitoringService, slowQueryRunRepository, fingerprintRepository,
            preAggregationAnalyzer);
        lenient().when(preAggregationAnalyzer.analyze(any(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "candidatesPerAngle", 20);
        ReflectionTestUtils.setField(service, "maxRewrites", 12);
        ReflectionTestUtils.setField(service, "regressionMinFactor", 1.3);
        ReflectionTestUtils.setField(service, "indexTopN", 10);

        // save() echoes the argument back so the service can keep mutating it.
        lenient().when(reportRepository.save(any(WorkloadAnalysisReport.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void runAnalysis_dedupesCandidateSet_andComposesAllSections() {
        String conn = "conn-1";
        String reportId = "rep-1";
        LocalDate day = LocalDate.of(2026, 6, 4);

        WorkloadAnalysisReport report = WorkloadAnalysisReport.builder()
            .id(reportId).connectionId(conn).status(WorkloadAnalysisReport.Status.PENDING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        when(slowQueryRunRepository.findTopByConnectionIdOrderByAnalyzedOnDesc(conn))
            .thenReturn(Optional.of(run("fpA", 9000, 30)));

        // fpA appears in BOTH most-affected and high-volume — must be counted once.
        when(slowQueryRunRepository.findMostAffected(eq(conn), eq(day), any(Pageable.class)))
            .thenReturn(List.of(run("fpA", 9000, 30), run("fpB", 4000, 10)));
        when(slowQueryRunRepository.findRegressions(eq(conn), eq(day), anyDouble()))
            .thenReturn(List.of(run("fpC", 2000, 5)));
        when(slowQueryRunRepository.findHighVolume(eq(conn), eq(day), any(Pageable.class)))
            .thenReturn(List.of(run("fpA", 9000, 30)));  // duplicate of most-affected

        // Index advisor returns one top rec.
        IndexRecommendationEntity rec = new IndexRecommendationEntity();
        rec.setId("idx-1");
        rec.setTableName("orders");
        rec.setColumnNames("customer_id");
        rec.setKind(IndexRecommendationEntity.Kind.CREATE_INDEX);
        rec.setPriority(IndexRecommendationEntity.Priority.HIGH);
        rec.setWorkloadScoreMs(50000L);
        rec.setWriteCostScore(2000L);
        rec.setEvidenceCount(7);
        rec.setReason("Hot WHERE customer_id across 47 queries");
        when(indexRecommendationService.getTopRecommendations(eq(conn), anyInt()))
            .thenReturn(List.of(rec));

        // Each candidate fingerprint resolves to a query text; optimizer returns a rewrite.
        QueryFingerprint fp = new QueryFingerprint();
        fp.setNormalizedQuery("SELECT * FROM orders WHERE customer_id = ?");
        fp.setSampleQuery("SELECT * FROM orders WHERE customer_id = 42");
        when(fingerprintRepository.findByConnectionIdAndFingerprint(eq(conn), any()))
            .thenReturn(Optional.of(fp));

        QueryOptimizationService.OptimizationResult opt =
            QueryOptimizationService.OptimizationResult.builder()
                .optimizedQuery("SELECT id, total FROM orders WHERE customer_id = ?")
                .estimatedImprovement(40.0)
                .explanation("Avoid SELECT * — fetch only needed columns")
                .build();
        when(queryOptimizationService.optimizeQuery(eq(conn), any(), any(), any())).thenReturn(opt);

        when(performanceMonitoringService.getUnusedIndexes(conn)).thenReturn(List.of());
        when(performanceMonitoringService.getDuplicateIndexes(conn)).thenReturn(List.of());

        service.runAnalysis(reportId);

        // Capture the final saved state.
        ArgumentCaptor<WorkloadAnalysisReport> cap = ArgumentCaptor.forClass(WorkloadAnalysisReport.class);
        org.mockito.Mockito.verify(reportRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        WorkloadAnalysisReport saved = cap.getValue();

        assertEquals(WorkloadAnalysisReport.Status.COMPLETED, saved.getStatus());
        assertEquals(100, saved.getProgress());
        // fpA, fpB, fpC — three distinct, NOT four (fpA deduped).
        assertEquals(3, saved.getCandidateCount());
        assertEquals(1, saved.getIndexRecCount());
        assertNotNull(saved.getReportData());
        assertEquals(1, saved.getReportData().getIndexRecommendations().size());
        assertEquals(48000L, saved.getReportData().getIndexRecommendations().get(0).getNetBenefit());
        // 3 candidates each produced a rewrite.
        assertEquals(3, saved.getRewriteRecCount());
        assertEquals(0, saved.getPreAggRecCount());
    }

    @Test
    void runAnalysis_noSlowQueryData_completesWithEmptyReport() {
        String conn = "conn-empty";
        String reportId = "rep-2";
        WorkloadAnalysisReport report = WorkloadAnalysisReport.builder()
            .id(reportId).connectionId(conn).status(WorkloadAnalysisReport.Status.PENDING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(slowQueryRunRepository.findTopByConnectionIdOrderByAnalyzedOnDesc(conn))
            .thenReturn(Optional.empty());
        when(indexRecommendationService.getTopRecommendations(eq(conn), anyInt())).thenReturn(List.of());
        when(performanceMonitoringService.getUnusedIndexes(conn)).thenReturn(List.of());
        when(performanceMonitoringService.getDuplicateIndexes(conn)).thenReturn(List.of());

        service.runAnalysis(reportId);

        assertEquals(WorkloadAnalysisReport.Status.COMPLETED, report.getStatus());
        assertEquals(0, report.getCandidateCount());
        assertNotNull(report.getReportData());
        assertTrue(report.getReportData().getSummary().getHeadline().toLowerCase().contains("no slow-query data"));
    }
}
