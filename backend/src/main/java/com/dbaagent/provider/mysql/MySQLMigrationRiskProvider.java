package com.dbaagent.provider.mysql;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.provider.api.MigrationRiskProvider;
import com.dbaagent.service.migration.DdlFacts;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL migration risk is not implemented. Returning UNKNOWN is deliberate: MySQL's
 * online-DDL matrix varies by version, storage engine and ALGORITHM/LOCK clause, and a
 * confident wrong answer about a lock is worse than no answer.
 */
@Component
public class MySQLMigrationRiskProvider implements MigrationRiskProvider {

    @Override
    public MigrationRiskReport classify(DdlFacts facts, TableFacts table) {
        return new MigrationRiskReport("mysql", "UNKNOWN", false, false,
                facts.operation().name(), facts.table(), List.of(), false,
                table.rowEstimate(), table.sizeBytes(), "unknown",
                "DeepSQL does not yet have verified MySQL DDL rules. Review this by hand.",
                null, "https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-operations.html",
                "unverified");
    }
}
