package com.dbaagent.service.pipeline;

import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.SamplingProvider;
import com.dbaagent.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ColumnValueFetcher {

    private static final Logger log = LoggerFactory.getLogger(ColumnValueFetcher.class);

    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;
    private final int maxValuesPerColumn;
    private final int maxFilterColumns;
    private final int queryTimeoutSeconds;
    private final Semaphore concurrencyLimit;

    public ColumnValueFetcher(ConnectionService connectionService,
                              DatabaseProviderRegistry providerRegistry,
                              int maxValuesPerColumn,
                              int maxFilterColumns,
                              int queryTimeoutSeconds) {
        this.connectionService = connectionService;
        this.providerRegistry = providerRegistry;
        this.maxValuesPerColumn = maxValuesPerColumn;
        this.maxFilterColumns = maxFilterColumns;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.concurrencyLimit = new Semaphore(5);
    }

    public ColumnValueContext fetch(String connectionId, String dbType, List<FilterColumn> filterColumns) {
        if (filterColumns.isEmpty()) {
            return ColumnValueContext.empty();
        }

        long start = System.currentTimeMillis();
        var columnsToFetch = filterColumns.stream()
            .limit(maxFilterColumns)
            .toList();

        var valueMap = new ConcurrentHashMap<String, List<String>>();
        var skipped = new CopyOnWriteArrayList<String>();
        long timeoutMs = queryTimeoutSeconds * 1000L;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = columnsToFetch.stream()
                .map(fc -> executor.submit(() -> fetchSingleColumn(connectionId, fc, dbType, valueMap, skipped)))
                .toList();

            for (var future : futures) {
                try {
                    future.get(timeoutMs + 1000L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    log.warn("Column value fetch timed out for connection {}", connectionId);
                } catch (Exception e) {
                    log.warn("Column value fetch failed: {}", e.getMessage());
                }
            }
        }

        long duration = System.currentTimeMillis() - start;
        String formatted = formatContextBlock(valueMap);

        return new ColumnValueContext(
            Map.copyOf(valueMap),
            formatted,
            duration,
            List.copyOf(skipped)
        );
    }

    private void fetchSingleColumn(String connectionId, FilterColumn fc,
                                   String dbType,
                                   Map<String, List<String>> valueMap,
                                   List<String> skipped) {
        concurrencyLimit.acquireUninterruptibly();
        try {
            JdbcTemplate jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);
            jdbc.setQueryTimeout(queryTimeoutSeconds);

            SamplingProvider sampling = providerRegistry.getDialect(dbType).sampling();
            String qCol = sampling.quoteIdentifier(fc.column());
            String qTable = sampling.quoteIdentifier(fc.table());
            String sql = String.format(
                "SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL ORDER BY %s LIMIT %d",
                qCol, qTable, qCol, qCol, maxValuesPerColumn
            );

            List<String> values = jdbc.queryForList(sql, String.class);
            if (!values.isEmpty()) {
                valueMap.put(fc.qualifiedName(), List.copyOf(values));
            }
        } catch (Exception e) {
            log.debug("Skipping column value fetch for {}: {}", fc.qualifiedName(), e.getMessage());
            skipped.add(fc.qualifiedName());
        } finally {
            concurrencyLimit.release();
        }
    }

    private String formatContextBlock(Map<String, List<String>> valueMap) {
        if (valueMap.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder("=== COLUMN VALUES FOR FILTERS ===\n");
        valueMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sb.append(entry.getKey())
                .append(": ")
                .append(String.join(", ", entry.getValue()))
                .append("\n"));
        sb.append("===\n");
        return sb.toString();
    }
}
