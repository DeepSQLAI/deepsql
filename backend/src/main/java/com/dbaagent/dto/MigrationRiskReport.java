package com.dbaagent.dto;

import java.util.List;

public record MigrationRiskReport(
        String dialect,
        String verdict,            // SAFE | CAUTION | DANGER | FAILS | UNKNOWN
        boolean safeToRun,
        boolean dialectSupported,
        String operation,
        String table,
        List<LockRef> locks,       // per table — a statement can lock tables it does not name
        boolean rewritesTable,
        long tableRows,
        long tableSizeBytes,
        String estimatedDuration,  // coarse bucket, never a number
        String reason,
        String saferAlternative,
        String docsUrl,
        String confidence) {

    public record LockRef(String table, String mode, List<String> blocks) {}
}
