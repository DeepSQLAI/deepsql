package com.dbaagent.service.brain.classification;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.TableStatsHistoryRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.brain.classification.GrowthPredictionService.GrowthPrediction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrowthPredictionService Unit Tests")
class GrowthPredictionServiceTest {

    @Mock private ConnectionService connectionService;
    @Mock private CredentialService credentialService;
    @Mock private TableStatsHistoryRepository tableStatsHistoryRepository;
    @Mock private Connection mockConnection;
    @Mock private PreparedStatement mockStmt;
    @Mock private ResultSet mockRs;

    private GrowthPredictionService service;

    @BeforeEach
    void setUp() {
        service = new GrowthPredictionService(connectionService, credentialService, tableStatsHistoryRepository);
    }

    // ─── Guard paths ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("predictGrowth guard paths")
    class GuardPaths {

        @Test
        @DisplayName("returns empty map when connection is not found")
        void returnsEmpty_whenConnectionNotFound() {
            when(credentialService.getDecryptedConnection("bad-id")).thenReturn(null);

            Map<String, GrowthPrediction> result = service.predictGrowth("bad-id");

            assertThat(result).isEmpty();
            verifyNoInteractions(connectionService, tableStatsHistoryRepository);
        }

        @Test
        @DisplayName("returns empty map gracefully when DB connection throws")
        void returnsEmpty_whenDbConnectionThrows() throws Exception {
            ConnectionRequest request = postgresRequest();
            when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
            when(connectionService.getConnection("conn-1", request))
                .thenThrow(new RuntimeException("DB unreachable"));

            Map<String, GrowthPrediction> result = service.predictGrowth("conn-1");

            assertThat(result).isEmpty();
        }
    }

    // ─── Basic predictions (no history) ──────────────────────────────────────

    @Nested
    @DisplayName("predictGrowth — no history")
    class NoHistory {

        @Test
        @DisplayName("returns STABLE category when no historical data is available")
        void returnsStableCategory_whenNoHistory() throws Exception {
            stubTableStats("users", 10_000L, 1024L * 1024L); // 1 MB, 10k rows
            when(tableStatsHistoryRepository
                .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                    eq("conn-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

            Map<String, GrowthPrediction> result = service.predictGrowth("conn-1");

            assertThat(result).containsKey("users");
            assertThat(result.get("users").getGrowthCategory()).isEqualTo("STABLE");
        }

        @Test
        @DisplayName("historicalDataPoints is 0 when repository returns empty list")
        void historicalDataPoints_isZero_whenNoHistory() throws Exception {
            stubTableStats("config", 500L, 512L * 1024L);
            when(tableStatsHistoryRepository
                .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                    eq("conn-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

            Map<String, GrowthPrediction> result = service.predictGrowth("conn-1");

            assertThat(result.get("config").getHistoricalDataPoints()).isZero();
        }

        @Test
        @DisplayName("currentSizeBytes and currentRowCount are populated from live DB stats")
        void currentStats_populatedFromDb() throws Exception {
            stubTableStats("orders", 50_000L, 5L * 1024L * 1024L); // 5 MB, 50k rows
            when(tableStatsHistoryRepository
                .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                    eq("conn-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

            Map<String, GrowthPrediction> result = service.predictGrowth("conn-1");

            GrowthPrediction pred = result.get("orders");
            assertThat(pred.getCurrentRowCount()).isEqualTo(50_000L);
            assertThat(pred.getCurrentSizeBytes()).isEqualTo(5L * 1024L * 1024L);
        }
    }

    // ─── Growth categorization with history ──────────────────────────────────

    @Nested
    @DisplayName("predictGrowth — growth categorization")
    class GrowthCategorization {

        @Test
        @DisplayName("classifies EXPLOSIVE when monthly growth exceeds 50%")
        void classifiesExplosive_whenMonthlyGrowthOver50Percent() throws Exception {
            // Current size: 10 MB; history shows start at 1 MB 60 days ago
            // dailyGrowthRate = (10MB - 1MB) / 60 = 150 KB/day
            // monthlyGrowthPct = (150KB * 30 / 1MB) * 100 = 450% → EXPLOSIVE
            long oneMB = 1024L * 1024L;
            long tenMB = 10L * 1024L * 1024L;
            LocalDateTime now = LocalDateTime.now();

            stubTableStats("events", 100_000L, tenMB);

            List<TableStatsHistory> history = List.of(
                buildHistory("events", now.minusDays(60), 10_000L, oneMB),
                buildHistory("events", now.minusDays(30), 50_000L, 5L * oneMB)
            );
            when(tableStatsHistoryRepository
                .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                    eq("conn-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(history);

            Map<String, GrowthPrediction> result = service.predictGrowth("conn-1");

            assertThat(result.get("events").getGrowthCategory()).isEqualTo("EXPLOSIVE");
        }

        @Test
        @DisplayName("classifies EXPLOSIVE growth alert in alerts list")
        void explosiveGrowthAlert_appearsInAlerts() throws Exception {
            long oneMB = 1024L * 1024L;
            long tenMB = 10L * 1024L * 1024L;
            LocalDateTime now = LocalDateTime.now();

            stubTableStats("events", 100_000L, tenMB);
            List<TableStatsHistory> history = List.of(
                buildHistory("events", now.minusDays(60), 10_000L, oneMB),
                buildHistory("events", now.minusDays(30), 50_000L, 5L * oneMB)
            );
            when(tableStatsHistoryRepository
                .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                    eq("conn-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(history);

            Map<String, GrowthPrediction> result = service.predictGrowth("conn-1");

            assertThat(result.get("events").getAlerts())
                .anyMatch(a -> a.contains("explosive"));
        }

        @Test
        @DisplayName("historical data points count matches number of history records")
        void historicalDataPoints_matchesHistorySize() throws Exception {
            LocalDateTime now = LocalDateTime.now();
            stubTableStats("logs", 1_000L, 1024L);

            List<TableStatsHistory> history = List.of(
                buildHistory("logs", now.minusDays(90), 900L, 900L),
                buildHistory("logs", now.minusDays(60), 950L, 950L),
                buildHistory("logs", now.minusDays(30), 980L, 980L)
            );
            when(tableStatsHistoryRepository
                .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                    eq("conn-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(history);

            Map<String, GrowthPrediction> result = service.predictGrowth("conn-1");

            assertThat(result.get("logs").getHistoricalDataPoints()).isEqualTo(3);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ConnectionRequest postgresRequest() {
        ConnectionRequest req = new ConnectionRequest();
        req.setDbType("postgresql");
        return req;
    }

    /**
     * Stubs credentialService + connectionService to return a Connection that reports
     * one table with the given row count and size.
     */
    private void stubTableStats(String tableName, long rowCount, long sizeBytes) throws Exception {
        ConnectionRequest request = postgresRequest();
        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
        when(connectionService.getConnection("conn-1", request)).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStmt);
        when(mockStmt.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true, false);
        when(mockRs.getString("table_name")).thenReturn(tableName);
        when(mockRs.getLong("row_count")).thenReturn(rowCount);
        when(mockRs.getLong("size_bytes")).thenReturn(sizeBytes);
    }

    private TableStatsHistory buildHistory(String table, LocalDateTime ts, long rows, long sizeBytes) {
        return TableStatsHistory.builder()
            .id(UUID.randomUUID().toString())
            .connectionId("conn-1")
            .tableName(table)
            .snapshotTimestamp(ts)
            .rowCount(rows)
            .sizeBytes(sizeBytes)
            .build();
    }
}
