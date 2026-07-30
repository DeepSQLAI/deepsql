package com.dbaagent.service.scheduler;

import com.dbaagent.model.SchemaDriftConfig;
import com.dbaagent.service.SchemaChangeTrackingService;
import com.dbaagent.service.SlowQueryDailyAnalysisService;
import com.dbaagent.service.brain.BrainLearningScheduler;
import com.dbaagent.service.brain.keycolumn.ColumnValueCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrainJobsServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BrainInitSchedulerService brainInitSchedulerService;
    @Mock private ColumnValueCollectionService columnValueCollectionService;
    @Mock private SchemaChangeTrackingService schemaChangeTrackingService;
    @Mock private BrainLearningScheduler brainLearningScheduler;
    @Mock private SlowQueryDailyAnalysisService slowQueryDailyAnalysisService;

    private BrainJobsService service;

    @BeforeEach
    void setUp() {
        service = new BrainJobsService(
            jdbcTemplate,
            brainInitSchedulerService,
            columnValueCollectionService,
            schemaChangeTrackingService,
            brainLearningScheduler,
            slowQueryDailyAnalysisService
        );
        ReflectionTestUtils.setField(service, "dbSchedulerEnabled", true);
        ReflectionTestUtils.setField(service, "brainRefreshEnabled", true);
        ReflectionTestUtils.setField(service, "columnValueRefreshEnabled", true);
        ReflectionTestUtils.setField(service, "brainLearningEnabled", true);
    }

    @Test
    void listJobs_marksMetadataRefreshRunningAndSchemaDriftActiveByDefault() throws Exception {
        OffsetDateTime nextRun = OffsetDateTime.of(2026, 4, 8, 9, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime lastSuccess = OffsetDateTime.of(2026, 4, 8, 2, 30, 0, 0, ZoneOffset.UTC);

        // toStatus() now calls ensureDefaultDriftConfig() which silently creates
        // a default config when none exists — so schema drift is always active
        // for any reachable connection.
        when(schemaChangeTrackingService.ensureDefaultDriftConfig("conn-1"))
            .thenReturn(new SchemaDriftConfig());
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                RowMapper<Object> mapper = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("task_name")).thenReturn("brain-refresh-metadata-lifecycle");
                when(rs.getObject("execution_time", OffsetDateTime.class)).thenReturn(nextRun);
                when(rs.getBoolean("picked")).thenReturn(true);
                when(rs.getObject("last_success", OffsetDateTime.class)).thenReturn(lastSuccess);
                when(rs.getObject("last_failure", OffsetDateTime.class)).thenReturn(null);
                when(rs.getObject("consecutive_failures")).thenReturn(0);
                when(rs.getObject("last_heartbeat", OffsetDateTime.class)).thenReturn(lastSuccess);
                return List.of(mapper.mapRow(rs, 0));
            });

        List<BrainJobsService.BrainJobStatus> jobs = service.listJobs("conn-1");

        BrainJobsService.BrainJobStatus metadataRefresh = jobs.stream()
            .filter(job -> "metadata_refresh".equals(job.key()))
            .findFirst()
            .orElseThrow();
        BrainJobsService.BrainJobStatus driftCheck = jobs.stream()
            .filter(job -> "schema_drift_check".equals(job.key()))
            .findFirst()
            .orElseThrow();

        assertEquals("running", metadataRefresh.status());
        assertTrue(metadataRefresh.running());
        assertEquals(nextRun, metadataRefresh.nextRunAt());

        // Schema drift now defaults to active because ensureDefaultDriftConfig
        // auto-provisions on first read. No recurring row in the mocked
        // jdbcTemplate result means statusReason flags db-scheduler registration.
        assertEquals("active", driftCheck.status());
        assertEquals("Recurring job is not registered in db-scheduler", driftCheck.statusReason());
    }

    @Test
    void runJob_dispatchesMetadataRefreshForConnection() {
        BrainJobsService.ManualRunResult result = service.runJob("conn-1", "metadata_refresh");

        assertEquals("started", result.status());
        verify(brainInitSchedulerService, timeout(1000)).planAndScheduleInit(eq("conn-1"), eq(false));
    }
}
