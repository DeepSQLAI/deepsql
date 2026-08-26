package com.dbaagent.controller;

import com.dbaagent.dto.SlowQueryInsightsResponse;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.service.CloudWatchLogFetchService;
import com.dbaagent.service.ExplainPlanService;
import com.dbaagent.service.OptimizationBenchmarkService;
import com.dbaagent.service.OptimizationCandidateService;
import com.dbaagent.service.QueryFingerprintService;
import com.dbaagent.service.QueryOptimizationService;
import com.dbaagent.service.S3LogFetchService;
import com.dbaagent.service.SlowQueryAlertService;
import com.dbaagent.service.SlowQueryDashboardService;
import com.dbaagent.service.SlowQueryHistoryService;
import com.dbaagent.service.SlowQueryInsightsService;
import com.dbaagent.service.SlowQueryLogParserService;
import com.dbaagent.service.SlowQueryService;
import com.dbaagent.service.KeyCustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.dbaagent.service.security.AccessControlService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlowQueryControllerS3Test {

    @Test
    void analyzeS3LogFile() throws Exception {
        SlowQueryService slowQueryService = mock(SlowQueryService.class);
        SlowQueryHistoryService historyService = mock(SlowQueryHistoryService.class);
        SlowQueryLogParserService logParserService = mock(SlowQueryLogParserService.class);
        S3LogFetchService s3LogFetchService = mock(S3LogFetchService.class);
        CloudWatchLogFetchService cloudWatchLogFetchService = mock(CloudWatchLogFetchService.class);
        QueryOptimizationService queryOptimizationService = mock(QueryOptimizationService.class);
        OptimizationCandidateService candidateService = mock(OptimizationCandidateService.class);
        OptimizationBenchmarkService optimizationBenchmarkService = mock(OptimizationBenchmarkService.class);
        SlowQueryAlertService slowQueryAlertService = mock(SlowQueryAlertService.class);
        QueryFingerprintService queryFingerprintService = mock(QueryFingerprintService.class);
        SlowQueryDashboardService slowQueryDashboardService = mock(SlowQueryDashboardService.class);
        ExplainPlanService explainPlanService = mock(ExplainPlanService.class);
        KeyCustomerService keyCustomerService = mock(KeyCustomerService.class);
        SlowQueryInsightsService slowQueryInsightsService = mock(SlowQueryInsightsService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        // The controller now authorizes the connection before doing any work; a plain
        // mock allows it, so these tests still exercise the S3 path they were written for.
        AccessControlService accessControlService = mock(AccessControlService.class);

        SlowQueryController controller = new SlowQueryController(
            slowQueryService,
            historyService,
            logParserService,
            s3LogFetchService,
            cloudWatchLogFetchService,
            queryOptimizationService,
            candidateService,
            optimizationBenchmarkService,
            slowQueryAlertService,
            queryFingerprintService,
            slowQueryDashboardService,
            explainPlanService,
            keyCustomerService,
            slowQueryInsightsService,
            objectMapper,
            accessControlService
        );

        SlowQueryAnalysis analysis = SlowQueryAnalysis.builder()
            .connectionId("conn-1")
            .build();

        when(s3LogFetchService.downloadLog(eq("s3://bucket/slow.log"), isNull()))
            .thenReturn(new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)));

        when(logParserService.parseAndAnalyze(any(InputStream.class), eq("mysql"), eq("conn-1")))
            .thenReturn(analysis);

        when(historyService.saveAnalysis(eq("conn-1"), eq(analysis), isNull()))
            .thenReturn(SlowQueryHistory.builder().id("hist-1").connectionId("conn-1").build());

        SlowQueryController.S3LogRequest request = new SlowQueryController.S3LogRequest();
        request.setConnectionId("conn-1");
        request.setS3Url("s3://bucket/slow.log");
        request.setDatabaseType("mysql");

        ResponseEntity<SlowQueryAnalysis> response = controller.analyzeSlowQueryLogFileFromS3(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("conn-1", response.getBody().getConnectionId());

        verify(s3LogFetchService).downloadLog("s3://bucket/slow.log", null);
        verify(historyService).saveAnalysis("conn-1", analysis, null);
    }

    @Test
    void getInsightsReturnsPayload() {
        SlowQueryService slowQueryService = mock(SlowQueryService.class);
        SlowQueryHistoryService historyService = mock(SlowQueryHistoryService.class);
        SlowQueryLogParserService logParserService = mock(SlowQueryLogParserService.class);
        S3LogFetchService s3LogFetchService = mock(S3LogFetchService.class);
        CloudWatchLogFetchService cloudWatchLogFetchService = mock(CloudWatchLogFetchService.class);
        QueryOptimizationService queryOptimizationService = mock(QueryOptimizationService.class);
        OptimizationCandidateService candidateService = mock(OptimizationCandidateService.class);
        OptimizationBenchmarkService optimizationBenchmarkService = mock(OptimizationBenchmarkService.class);
        SlowQueryAlertService slowQueryAlertService = mock(SlowQueryAlertService.class);
        QueryFingerprintService queryFingerprintService = mock(QueryFingerprintService.class);
        SlowQueryDashboardService slowQueryDashboardService = mock(SlowQueryDashboardService.class);
        ExplainPlanService explainPlanService = mock(ExplainPlanService.class);
        KeyCustomerService keyCustomerService = mock(KeyCustomerService.class);
        SlowQueryInsightsService slowQueryInsightsService = mock(SlowQueryInsightsService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        // The controller now authorizes the connection before doing any work; a plain
        // mock allows it, so these tests still exercise the S3 path they were written for.
        AccessControlService accessControlService = mock(AccessControlService.class);

        SlowQueryController controller = new SlowQueryController(
            slowQueryService,
            historyService,
            logParserService,
            s3LogFetchService,
            cloudWatchLogFetchService,
            queryOptimizationService,
            candidateService,
            optimizationBenchmarkService,
            slowQueryAlertService,
            queryFingerprintService,
            slowQueryDashboardService,
            explainPlanService,
            keyCustomerService,
            slowQueryInsightsService,
            objectMapper,
            accessControlService
        );

        SlowQueryInsightsResponse payload = SlowQueryInsightsResponse.builder()
            .metadata(SlowQueryInsightsResponse.InsightMetadata.builder()
                .window("7d")
                .build())
            .build();

        when(slowQueryInsightsService.getInsights("conn-1", "7d", 10)).thenReturn(payload);

        ResponseEntity<SlowQueryInsightsResponse> response = controller.getInsights("conn-1", "7d", 10);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("7d", response.getBody().getMetadata().getWindow());
    }
}
