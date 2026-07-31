package com.dbaagent.service.pipeline;

import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.SamplingProvider;
import com.dbaagent.service.ConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ColumnValueFetcherTest {

    @Mock private ConnectionService connectionService;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private DatabaseDialect mockDialect;
    @Mock private SamplingProvider mockSampling;
    @Mock private JdbcTemplate jdbcTemplate;

    private ColumnValueFetcher fetcher;

    @BeforeEach
    void setUp() {
        lenient().doReturn(mockDialect).when(providerRegistry).getDialect(anyString());
        lenient().doReturn(mockSampling).when(mockDialect).sampling();
        lenient().doAnswer(inv -> "\"" + inv.getArgument(0) + "\"")
            .when(mockSampling).quoteIdentifier(anyString());

        fetcher = new ColumnValueFetcher(connectionService, providerRegistry, 50, 10, 3);
    }

    @Test
    void fetchesDistinctValuesForFilterColumns() {
        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doReturn(List.of("CONFIRMED", "CANCELLED", "PENDING"))
            .when(jdbcTemplate).queryForList(contains("bookings"), eq(String.class));

        var filterColumns = List.of(new FilterColumn("bookings", "status"));
        var result = fetcher.fetch("conn-1", "POSTGRESQL", filterColumns);

        assertThat(result.valueMap()).containsKey("bookings.status");
        assertThat(result.valueMap().get("bookings.status"))
            .containsExactly("CONFIRMED", "CANCELLED", "PENDING");
        assertThat(result.formattedContext()).contains("bookings.status:");
        assertThat(result.formattedContext()).contains("CONFIRMED");
    }

    @Test
    void skipsColumnOnTimeout() {
        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doThrow(new org.springframework.dao.QueryTimeoutException("timeout"))
            .when(jdbcTemplate).queryForList(anyString(), eq(String.class));

        var filterColumns = List.of(new FilterColumn("large_table", "id"));
        var result = fetcher.fetch("conn-1", "POSTGRESQL", filterColumns);

        assertThat(result.valueMap()).isEmpty();
        assertThat(result.columnsSkipped()).contains("large_table.id");
    }

    @Test
    void limitsMaxFilterColumns() {
        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doReturn(List.of("val1")).when(jdbcTemplate).queryForList(anyString(), eq(String.class));

        var filterColumns = new ArrayList<FilterColumn>();
        for (int i = 0; i < 15; i++) {
            filterColumns.add(new FilterColumn("t" + i, "c" + i));
        }

        var result = fetcher.fetch("conn-1", "POSTGRESQL", filterColumns);
        assertThat(result.valueMap()).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void emptyFilterColumnsReturnsEmptyContext() {
        var result = fetcher.fetch("conn-1", "POSTGRESQL", List.of());
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.formattedContext()).isEmpty();
    }

    @Test
    void formatsContextBlockCorrectly() {
        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doReturn(List.of("CONFIRMED", "CANCELLED"))
            .when(jdbcTemplate).queryForList(contains("bookings"), eq(String.class));
        doReturn(List.of("Mumbai", "Delhi"))
            .when(jdbcTemplate).queryForList(contains("customers"), eq(String.class));

        var filterColumns = List.of(
            new FilterColumn("bookings", "status"),
            new FilterColumn("customers", "city")
        );
        var result = fetcher.fetch("conn-1", "POSTGRESQL", filterColumns);

        assertThat(result.formattedContext())
            .startsWith("=== COLUMN VALUES FOR FILTERS ===")
            .endsWith("===\n")
            .contains("bookings.status: CONFIRMED, CANCELLED")
            .contains("customers.city: Mumbai, Delhi");
    }
}
