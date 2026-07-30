package com.dbaagent.provider.mysql;

import com.dbaagent.provider.api.SamplingProvider;
import org.springframework.stereotype.Component;

/**
 * MySQL-specific sampling and identifier quoting.
 * Uses backtick quoting and RAND() for random ordering.
 */
@Component
public class MySQLSamplingProvider implements SamplingProvider {

    private static final String DATABASE_TYPE = "mysql";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null) return "";
        String trimmed = identifier.trim();
        return "`" + trimmed.replace("`", "``") + "`";
    }

    @Override
    public String qualifyTable(String schemaName, String tableName) {
        if (schemaName == null || schemaName.isBlank()) {
            return quoteIdentifier(tableName);
        }
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    @Override
    public String buildRandomSampleSql(String tableRef, int limit) {
        return "SELECT * FROM " + tableRef + " ORDER BY RAND() LIMIT " + limit;
    }

    @Override
    public String buildRecentSampleSql(String tableRef, String quotedTemporalColumn, int limit) {
        return "SELECT * FROM " + tableRef
            + " ORDER BY " + quotedTemporalColumn + " DESC LIMIT " + limit;
    }

    @Override
    public String buildStratifiedSampleSql(String tableRef, String quotedTemporalColumn,
                                            int recentCount, int randomCount) {
        return "(SELECT * FROM " + tableRef
            + " ORDER BY " + quotedTemporalColumn + " DESC LIMIT " + recentCount + ")"
            + " UNION ALL "
            + "(SELECT * FROM " + tableRef + " ORDER BY RAND() LIMIT " + randomCount + ")";
    }
}
