package com.dbaagent.service.migration;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationRiskService {

    private final DdlStatementParser parser;
    private final DatabaseProviderRegistry registry;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final AccessControlService accessControlService;

    public MigrationRiskReport analyze(String connectionId, String sql) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        var parsed = parser.parse(sql);
        if (parsed.isEmpty()) {
            return failClosed("DeepSQL could not parse this statement, so it cannot verify what "
                    + "it will do. Review it by hand before running it.");
        }
        var facts = parsed.get();

        var request = credentialService.getDecryptedConnection(connectionId);
        var dialect = registry.getDialect(request.getDbType());
        var provider = dialect.migrationRisk();

        TableFacts table = tableFacts(connectionId, request, facts.table());
        return provider.classify(facts, table);
    }

    private TableFacts tableFacts(String connectionId, ConnectionRequest request, String table) {
        try {
            var jdbc = connectionService.getJdbcTemplate(connectionId, request);
            var row = jdbc.queryForMap("""
                    SELECT COALESCE(c.reltuples, 0)::bigint AS rows,
                           COALESCE(pg_total_relation_size(c.oid), 0)::bigint AS bytes
                    FROM pg_class c WHERE c.oid = ?::regclass
                    """, table);
            long rows = ((Number) row.get("rows")).longValue();
            long bytes = ((Number) row.get("bytes")).longValue();
            return new TableFacts(Math.max(rows, 0), bytes, rows == 0);
        } catch (Exception e) {
            // Unknown size is not a reason to claim safety — assume large.
            log.warn("Could not read table facts for {}: {}", table, e.getMessage());
            return new TableFacts(Long.MAX_VALUE, 0L, false);
        }
    }

    private MigrationRiskReport failClosed(String reason) {
        return new MigrationRiskReport("unknown", "UNKNOWN", false, true, "UNKNOWN", null,
                List.of(), false, 0L, 0L, "unknown", reason, null, null, "unverified");
    }
}
