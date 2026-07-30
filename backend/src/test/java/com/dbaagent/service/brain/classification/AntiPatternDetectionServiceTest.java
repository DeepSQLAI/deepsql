package com.dbaagent.service.brain.classification;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.brain.classification.AntiPatternDetectionService.DetectedAntiPattern;
import com.dbaagent.service.brain.classification.AntiPatternDetectionService.TableAntiPatterns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AntiPatternDetectionService Unit Tests")
class AntiPatternDetectionServiceTest {

    @Mock private ConnectionService connectionService;
    @Mock private CredentialService credentialService;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private Connection mockConnection;
    @Mock private PreparedStatement metadataStmt;
    @Mock private PreparedStatement indexStmt;
    @Mock private PreparedStatement columnNamesStmt;
    @Mock private PreparedStatement scanStatsStmt;
    @Mock private ResultSet metadataRs;
    @Mock private ResultSet emptyRs;

    private AntiPatternDetectionService service;

    @BeforeEach
    void setUp() {
        service = new AntiPatternDetectionService(connectionService, credentialService, keyColumnAnalysisRepository);
    }

    // ─── Guard / fast-path tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("detectAntiPatterns guard paths")
    class GuardPaths {

        @Test
        @DisplayName("returns empty map when connection is not found")
        void returnsEmpty_whenConnectionNotFound() {
            when(credentialService.getDecryptedConnection("unknown")).thenReturn(null);

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("unknown");

            assertThat(result).isEmpty();
            verifyNoInteractions(connectionService, keyColumnAnalysisRepository);
        }

        @Test
        @DisplayName("returns empty map when getConnection throws — logs error gracefully")
        void returnsEmpty_whenConnectionThrows() throws Exception {
            ConnectionRequest request = postgresRequest();
            when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
            when(connectionService.getConnection("conn-1", request))
                .thenThrow(new RuntimeException("host unreachable"));

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("conn-1");

            assertThat(result).isEmpty();
        }
    }

    // ─── GOD_TABLE detection (via mock JDBC) ─────────────────────────────────

    @Nested
    @DisplayName("detectAntiPatterns — GOD_TABLE")
    class GodTableDetection {

        @Test
        @DisplayName("detects GOD_TABLE when a table has more than 50 columns")
        void detectsGodTable_whenOver50Columns() throws Exception {
            // Return one table: "big_table" with 55 columns, 1000 rows
            stubConnectionWithOneTable("big_table", 55, 1000L);

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("conn-1");

            assertThat(result).containsKey("big_table");
            assertThat(result.get("big_table").getAntiPatterns())
                .extracting(DetectedAntiPattern::getType)
                .contains("GOD_TABLE");
        }

        @Test
        @DisplayName("does not detect GOD_TABLE when column count is exactly 50")
        void doesNotDetectGodTable_atBoundary50() throws Exception {
            stubConnectionWithOneTable("normal_table", 50, 1000L);

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("conn-1");

            assertThat(result).containsKey("normal_table");
            assertThat(result.get("normal_table").getAntiPatterns())
                .extracting(DetectedAntiPattern::getType)
                .doesNotContain("GOD_TABLE");
        }
    }

    // ─── WIDE_TABLE detection ─────────────────────────────────────────────────

    @Nested
    @DisplayName("detectAntiPatterns — WIDE_TABLE")
    class WideTableDetection {

        @Test
        @DisplayName("detects WIDE_TABLE when column count is between 31 and 50")
        void detectsWideTable_between31And50Columns() throws Exception {
            stubConnectionWithOneTable("orders", 35, 5000L);

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("conn-1");

            assertThat(result).containsKey("orders");
            assertThat(result.get("orders").getAntiPatterns())
                .extracting(DetectedAntiPattern::getType)
                .contains("WIDE_TABLE")
                .doesNotContain("GOD_TABLE");
        }

        @Test
        @DisplayName("does not detect WIDE_TABLE for narrow tables (≤30 columns)")
        void doesNotDetectWideTable_forNarrowTable() throws Exception {
            stubConnectionWithOneTable("users", 10, 5000L);

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("conn-1");

            assertThat(result).containsKey("users");
            assertThat(result.get("users").getAntiPatterns())
                .extracting(DetectedAntiPattern::getType)
                .doesNotContain("WIDE_TABLE");
        }
    }

    // ─── calculateOverallSeverity ─────────────────────────────────────────────

    @Nested
    @DisplayName("calculateOverallSeverity")
    class CalculateOverallSeverity {

        private Method calculateOverallSeverity;

        @BeforeEach
        void reflectMethod() throws Exception {
            calculateOverallSeverity = AntiPatternDetectionService.class
                .getDeclaredMethod("calculateOverallSeverity", List.class);
            calculateOverallSeverity.setAccessible(true);
        }

        @Test
        @DisplayName("returns NONE when anti-pattern list is empty")
        void returnsNone_whenEmpty() throws Exception {
            String severity = (String) calculateOverallSeverity.invoke(service, List.of());
            assertThat(severity).isEqualTo("NONE");
        }

        @Test
        @DisplayName("returns HIGH when any pattern has HIGH severity")
        void returnsHigh_whenAnyPatternIsHigh() throws Exception {
            List<DetectedAntiPattern> patterns = List.of(
                DetectedAntiPattern.builder().type("WIDE_TABLE").severity("MEDIUM").build(),
                DetectedAntiPattern.builder().type("GOD_TABLE").severity("HIGH").build()
            );
            String severity = (String) calculateOverallSeverity.invoke(service, patterns);
            assertThat(severity).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("returns CRITICAL when any pattern has CRITICAL severity")
        void returnsCritical_whenAnyPatternIsCritical() throws Exception {
            List<DetectedAntiPattern> patterns = List.of(
                DetectedAntiPattern.builder().type("SOME_PATTERN").severity("CRITICAL").build(),
                DetectedAntiPattern.builder().type("GOD_TABLE").severity("HIGH").build()
            );
            String severity = (String) calculateOverallSeverity.invoke(service, patterns);
            assertThat(severity).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("returns MEDIUM when patterns are only MEDIUM severity")
        void returnsMedium_whenAllPatternsMedium() throws Exception {
            List<DetectedAntiPattern> patterns = List.of(
                DetectedAntiPattern.builder().type("WIDE_TABLE").severity("MEDIUM").build(),
                DetectedAntiPattern.builder().type("SPARSE_TABLE").severity("MEDIUM").build()
            );
            String severity = (String) calculateOverallSeverity.invoke(service, patterns);
            assertThat(severity).isEqualTo("MEDIUM");
        }

        @Test
        @DisplayName("returns LOW when patterns are only LOW severity")
        void returnsLow_whenAllPatternsLow() throws Exception {
            List<DetectedAntiPattern> patterns = List.of(
                DetectedAntiPattern.builder().type("MISSING_TIMESTAMP").severity("LOW").build()
            );
            String severity = (String) calculateOverallSeverity.invoke(service, patterns);
            assertThat(severity).isEqualTo("LOW");
        }
    }

    // ─── overallSeverity field on result ─────────────────────────────────────

    @Nested
    @DisplayName("detectAntiPatterns — result overallSeverity field")
    class ResultSeverity {

        @Test
        @DisplayName("overallSeverity is HIGH when GOD_TABLE is detected")
        void overallSeverityIsHigh_whenGodTableDetected() throws Exception {
            stubConnectionWithOneTable("monster_table", 60, 5000L);

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("conn-1");

            assertThat(result.get("monster_table").getOverallSeverity()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("overallSeverity is NONE for a clean narrow table")
        void overallSeverityIsNone_forCleanTable() throws Exception {
            stubConnectionWithOneTable("config", 5, 100L);

            Map<String, TableAntiPatterns> result = service.detectAntiPatterns("conn-1");

            assertThat(result.get("config").getOverallSeverity()).isEqualTo("NONE");
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private ConnectionRequest postgresRequest() {
        ConnectionRequest req = new ConnectionRequest();
        req.setDbType("postgresql");
        return req;
    }

    /**
     * Stubs credentialService + connectionService to return a mock Connection that
     * reports one table with the given parameters.  All other SQL calls (indexes,
     * column names, scan stats) return empty result sets.
     */
    private void stubConnectionWithOneTable(String tableName, int columnCount, long rowCount)
            throws Exception {
        ConnectionRequest request = postgresRequest();
        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
        when(connectionService.getConnection("conn-1", request)).thenReturn(mockConnection);
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("conn-1"))
            .thenReturn(List.of());

        // Stub the metadata ResultSet to return one row then stop
        when(metadataRs.next()).thenReturn(true, false);
        when(metadataRs.getString("table_name")).thenReturn(tableName);
        when(metadataRs.getInt("column_count")).thenReturn(columnCount);
        when(metadataRs.getLong("row_count")).thenReturn(rowCount);

        // First prepareStatement call = metadata query
        when(metadataStmt.executeQuery()).thenReturn(metadataRs);
        // Remaining calls return empty ResultSets
        when(emptyRs.next()).thenReturn(false);
        when(indexStmt.executeQuery()).thenReturn(emptyRs);
        when(columnNamesStmt.executeQuery()).thenReturn(emptyRs);
        when(scanStatsStmt.executeQuery()).thenReturn(emptyRs);

        // Sequence: metadata → indexes → (per-table: column names) → scan stats
        when(mockConnection.prepareStatement(anyString()))
            .thenReturn(metadataStmt, indexStmt, columnNamesStmt, scanStatsStmt);
    }
}
