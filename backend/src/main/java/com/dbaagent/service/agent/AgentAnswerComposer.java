package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AgentAnswerComposer {

    public AgentExecutionResult compose(AgentPlan plan, AgentExecutionContext context) {
        return switch (plan.intent()) {
            case SUBSCRIPTION_REVENUE, CHURN_RISK, ACCOUNTS_MODULE, UNIVERSAL_CHAT -> composeUniversal(plan, context);
            case METADATA_ANALYSIS -> composeMetadataAnalysis(plan, context);
            case NONE -> throw new IllegalArgumentException("Cannot compose NONE intent");
        };
    }

    @SuppressWarnings("unchecked")
    private AgentExecutionResult composeMetadataAnalysis(AgentPlan plan, AgentExecutionContext context) {
        VerifiedAnswer finalMetadataAnswer = context.getMemory("metadataFinalAnswer");
        if (finalMetadataAnswer != null) {
            return new AgentExecutionResult(
                null,
                plan.intent(),
                finalMetadataAnswer.renderedMessage(),
                context.getMemory("liveMetadataResult"),
                plan.goal(),
                context.executedQueries(),
                context.toolsUsed(),
                finalMetadataAnswer.evidence() != null ? finalMetadataAnswer.evidence().confidence() : 0.9,
                context.taskResults(),
                finalMetadataAnswer.promptIntent(),
                finalMetadataAnswer.answerContract(),
                finalMetadataAnswer.verificationReport()
            );
        }

        StringBuilder sb = new StringBuilder();
        double confidence = 0.5;

        // Check vault data first
        List<AgentObservation> observations = context.observations();
        AgentObservation vaultObs = observations.stream()
            .filter(o -> o.type() != null && o.type().startsWith("vault_"))
            .findFirst().orElse(null);

        boolean vaultSufficient = false;
        if (vaultObs != null && vaultObs.data() != null) {
            vaultSufficient = Boolean.TRUE.equals(vaultObs.data().get("sufficient"));
        }

        if (vaultSufficient && vaultObs != null) {
            confidence = 0.9;

            Map<String, Object> data = vaultObs.data();

            if ("table_columns".equals(Objects.toString(data.get("answerType"), ""))) {
                confidence = 0.96;
                String tableName = Objects.toString(data.get("tableName"), "table");
                Object columnCount = data.getOrDefault("columnCount", 0);
                sb.append("Table `").append(tableName).append("` has **").append(columnCount).append(" columns**.\n\n");
                Object columns = data.get("columns");
                if (columns instanceof List<?> columnList && !columnList.isEmpty()) {
                    sb.append("Columns:\n");
                    for (Object item : columnList) {
                        if (item instanceof Map<?, ?> row) {
                            sb.append("- ").append(formatVaultColumnLine(row)).append("\n");
                        }
                    }
                }
                return new AgentExecutionResult(
                    null,
                    plan.intent(),
                    sb.toString(),
                    null,
                    plan.goal(),
                    context.executedQueries(),
                    context.toolsUsed(),
                    confidence
                );
            }

            if ("table_key_columns".equals(Objects.toString(data.get("answerType"), ""))) {
                confidence = 0.96;
                String tableName = Objects.toString(data.get("tableName"), "table");
                sb.append("The most relevant key columns in `").append(tableName).append("` are:\n");
                Object keyColumns = data.get("keyColumns");
                if (keyColumns instanceof List<?> columnList && !columnList.isEmpty()) {
                    sb.append("\n");
                    for (Object item : columnList) {
                        if (item instanceof Map<?, ?> row) {
                            sb.append("- ").append(formatVaultKeyColumnLine(row)).append("\n");
                        }
                    }
                }
                return new AgentExecutionResult(
                    null,
                    plan.intent(),
                    sb.toString(),
                    null,
                    plan.goal(),
                    context.executedQueries(),
                    context.toolsUsed(),
                    confidence
                );
            }

            if ("table_row_count".equals(Objects.toString(data.get("answerType"), ""))) {
                confidence = 0.96;
                String tableName = Objects.toString(data.get("tableName"), "table");
                Object rowCount = data.get("rowCount");
                sb.append("Table `")
                    .append(tableName)
                    .append("` has an estimated **")
                    .append(Objects.toString(rowCount, "unknown"))
                    .append(" rows** in the current schema snapshot.");
                return new AgentExecutionResult(
                    null,
                    plan.intent(),
                    sb.toString(),
                    null,
                    plan.goal(),
                    context.executedQueries(),
                    context.toolsUsed(),
                    confidence
                );
            }

            if ("schema_snapshot_count".equals(Objects.toString(data.get("answerType"), ""))) {
                confidence = 0.98;
                String connectionName = Objects.toString(data.get("connectionName"), "this connection");
                Object snapshotCount = data.getOrDefault("snapshotCount", 0);
                sb.append("Connection `")
                    .append(connectionName)
                    .append("` has **")
                    .append(snapshotCount)
                    .append(" schema snapshots** stored in the vault DB.\n\n");
                if (data.get("latestCapturedAt") != null) {
                    sb.append("Latest snapshot captured at `")
                        .append(data.get("latestCapturedAt"))
                        .append("`");
                    if (data.get("latestSnapshotType") != null) {
                        sb.append(" (`").append(data.get("latestSnapshotType")).append("`)");
                    }
                    sb.append(".\n\n");
                }
                sb.append("Source: Vault DB schema snapshot history.\n");
                return new AgentExecutionResult(
                    null,
                    plan.intent(),
                    sb.toString(),
                    null,
                    plan.goal(),
                    context.executedQueries(),
                    context.toolsUsed(),
                    confidence
                );
            }

            if ("table_indexes".equals(Objects.toString(data.get("answerType"), ""))) {
                confidence = 0.96;
                String tableName = Objects.toString(data.get("tableName"), "table");
                Object indexCount = data.getOrDefault("indexCount", 0);
                sb.append("Table `").append(tableName).append("` has **").append(indexCount)
                    .append(" indexes** in the current schema snapshot.\n");
                Object indexes = data.get("indexes");
                if (indexes instanceof List<?> indexList && !indexList.isEmpty()) {
                    sb.append("\nIndexes:\n");
                    for (Object item : indexList) {
                        if (item instanceof Map<?, ?> row) {
                            sb.append("- ").append(formatVaultIndexLine(row)).append("\n");
                        }
                    }
                }
                return new AgentExecutionResult(
                    null,
                    plan.intent(),
                    sb.toString(),
                    null,
                    plan.goal(),
                    context.executedQueries(),
                    context.toolsUsed(),
                    confidence
                );
            }

            // Format inferred relationships
            Object inferredRels = data.get("inferredRelationships");
            if (inferredRels instanceof List<?> relList && !relList.isEmpty()) {
                sb.append("**Inferred Join Paths** (from query pattern analysis):\n\n");
                for (Object item : relList) {
                    if (item instanceof Map<?, ?> row) {
                        sb.append("- `").append(row.get("source")).append("` -> `").append(row.get("target")).append("`");
                        Object joinCount = row.get("joinCount");
                        Object conf = row.get("confidence");
                        Object cardinality = row.get("cardinality");
                        if (joinCount != null) {
                            sb.append(" — observed in **").append(joinCount).append("** queries");
                        }
                        if (conf != null) {
                            sb.append(", ").append(conf).append("% confidence");
                        }
                        if (cardinality != null) {
                            sb.append(" [").append(cardinality).append("]");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            // Format classified relationships
            Object classifiedRels = data.get("classifiedRelationships");
            if (classifiedRels instanceof List<?> classList && !classList.isEmpty()) {
                sb.append("**Relationship Classifications**:\n\n");
                for (Object item : classList) {
                    if (item instanceof Map<?, ?> row) {
                        sb.append("- `").append(row.get("source")).append("` -> `").append(row.get("target")).append("`");
                        Object type = row.get("type");
                        Object strength = row.get("strength");
                        Object freq = row.get("joinFrequency");
                        if (type != null) sb.append(" (").append(type).append(")");
                        if (strength != null) sb.append(" [").append(strength).append("]");
                        if (freq != null && !"0".equals(freq.toString())) {
                            sb.append(" — join frequency: ").append(freq);
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            // Format key columns
            Object keyColumns = data.get("keyColumns");
            if (keyColumns instanceof List<?> colList && !colList.isEmpty()) {
                sb.append("**Key Columns**:\n\n");
                for (Object item : colList) {
                    if (item instanceof Map<?, ?> row) {
                        sb.append("- `").append(row.get("table")).append(".").append(row.get("column"))
                            .append("` — importance: ").append(row.get("importance")).append("\n");
                    }
                }
                sb.append("\n");
            }

            Object directMatches = data.get("directMatches");
            if (directMatches instanceof List<?> directList && !directList.isEmpty()) {
                sb.append("Related tables in this schema:\n\n");
                for (Object item : directList) {
                    if (item instanceof Map<?, ?> row) {
                        sb.append("- `").append(row.get("table")).append("`");
                        if (row.get("role") != null) {
                            sb.append(" — role: **").append(row.get("role")).append("**");
                        }
                        if (row.get("businessDomain") != null) {
                            sb.append(", domain: ").append(row.get("businessDomain"));
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            Object suggestedExistingTables = data.get("suggestedExistingTables");
            if (suggestedExistingTables instanceof List<?> existingList && !existingList.isEmpty()) {
                if (Boolean.TRUE.equals(data.get("noDirectModuleTablesFound"))) {
                    sb.append("I do **not** see an obvious task-management module already present by table name in this schema.\n\n");
                    sb.append("The closest existing anchor tables I would reuse are:\n\n");
                } else {
                    sb.append("**Useful Existing Anchor Tables**:\n\n");
                }
                for (Object item : existingList) {
                    if (item instanceof Map<?, ?> row) {
                        sb.append("- `").append(row.get("table")).append("`");
                        if (row.get("reason") != null) {
                            sb.append(" — ").append(row.get("reason"));
                        }
                        Object columns = row.get("columns");
                        if (columns instanceof List<?> cols && !cols.isEmpty()) {
                            sb.append(" (columns: ");
                            sb.append(cols.stream().map(Object::toString).reduce((a, b) -> a + ", " + b).orElse(""));
                            sb.append(")");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            Object proposedTables = data.get("proposedTables");
            if (proposedTables instanceof List<?> proposedList && !proposedList.isEmpty()) {
                sb.append("If you want a clean task-management module, I would add these tables:\n\n");
                for (Object item : proposedList) {
                    if (item instanceof Map<?, ?> row) {
                        sb.append("- `").append(row.get("table")).append("`");
                        if (row.get("purpose") != null) {
                            sb.append(" — ").append(row.get("purpose"));
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            if ("table_count".equals(Objects.toString(data.get("answerType"), ""))) {
                sb.append("This connection currently has **")
                    .append(data.getOrDefault("tableCount", 0))
                    .append("** cached tables in vault metadata.\n\n");
            }

            // Format classifications
            Object classifications = data.get("classifications");
            if (classifications instanceof List<?> clList && !clList.isEmpty()) {
                sb.append("**Table Classifications**:\n\n");
                for (Object item : clList) {
                    if (item instanceof Map<?, ?> row) {
                        sb.append("- `").append(row.get("table")).append("` — role: **").append(row.get("role")).append("**");
                        if (row.get("businessDomain") != null) {
                            sb.append(", domain: ").append(row.get("businessDomain"));
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            // Workload/tuning data
            if (data.containsKey("workloadType")) {
                sb.append("**Workload**: ").append(data.get("workloadType"));
                if (data.get("confidence") != null) {
                    sb.append(" (").append(data.get("confidence")).append("% confidence)");
                }
                sb.append("\n\n");
            }

            if (Boolean.TRUE.equals(data.get("noDirectModuleTablesFound"))) {
                sb.append("Source: Cached vault DB metadata plus the current schema snapshot.\n");
            } else {
                sb.append("Source: Cached vault DB metadata (pre-analyzed from query patterns and schema analysis).\n");
            }
        } else {
            // Use live metadata results
            AgentObservation liveObs = observations.stream()
                .filter(o -> o.type() != null && o.type().startsWith("live_metadata"))
                .findFirst().orElse(null);

            QueryResult liveResult = context.getMemory("liveMetadataResult");

            if (liveResult != null && liveResult.getRows() != null && !liveResult.getRows().isEmpty()) {
                confidence = 0.8;
                String liveAnswerType = context.getMemory("liveMetadataAnswerType");
                String liveTableName = context.getMemory("liveMetadataTableName");

                if ("table_row_count".equals(liveAnswerType)) {
                    Object rowCount = liveResult.getRows().getFirst().size() > 1
                        ? liveResult.getRows().getFirst().get(1)
                        : liveResult.getRows().getFirst().getFirst();
                    sb.append("Table `")
                        .append(Objects.toString(liveTableName, "table"))
                        .append("` has an estimated **")
                        .append(Objects.toString(rowCount, "unknown"))
                        .append(" rows** from the live database catalogs.");
                } else if ("table_indexes".equals(liveAnswerType)) {
                    sb.append("Table `")
                        .append(Objects.toString(liveTableName, "table"))
                        .append("` has **")
                        .append(liveResult.getRows().size())
                        .append(" indexes** in the live database catalogs.\n\n");
                    sb.append("Indexes:\n");
                    for (List<Object> row : liveResult.getRows().stream().limit(50).toList()) {
                        sb.append("- ").append(formatLiveIndexLine(row)).append("\n");
                    }
                } else if ("table_columns".equals(liveAnswerType)) {
                    sb.append("Table `")
                        .append(Objects.toString(liveTableName, "table"))
                        .append("` has **")
                        .append(liveResult.getRows().size())
                        .append(" columns** in the live database catalogs.\n\n");
                    sb.append("Columns:\n");
                    for (List<Object> row : liveResult.getRows().stream().limit(50).toList()) {
                        sb.append("- ").append(formatLiveColumnLine(row)).append("\n");
                    }
                } else {
                    sb.append("### Database Metadata Analysis\n\n");
                    sb.append("Queried live database metadata catalogs:\n\n");

                    // Format as markdown table
                    List<String> columns = liveResult.getColumns();
                    if (columns != null && !columns.isEmpty()) {
                        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
                        sb.append("| ").append(columns.stream().map(c -> "---").reduce((a, b) -> a + " | " + b).orElse("---")).append(" |\n");

                        for (List<Object> row : liveResult.getRows().stream().limit(15).toList()) {
                            sb.append("| ");
                            for (int i = 0; i < columns.size(); i++) {
                                Object val = i < row.size() ? row.get(i) : "";
                                String display = val == null ? "" : Objects.toString(val);
                                if (display.length() > 60) {
                                    display = display.substring(0, 57) + "...";
                                }
                                sb.append(display);
                                if (i < columns.size() - 1) sb.append(" | ");
                            }
                            sb.append(" |\n");
                        }
                        sb.append("\n");
                    }

                    String liveSql = context.getMemory("liveMetadataSql");
                    if (liveSql != null) {
                        sb.append("Source: Live database metadata query.\n");
                    }
                }
            } else {
                confidence = 0.3;
                sb.append("### Metadata Analysis\n\n");
                sb.append("No cached metadata was found in the vault DB for this connection, and the live metadata query ");
                if (liveObs != null && "live_metadata_error".equals(liveObs.type())) {
                    sb.append("encountered an error: ").append(liveObs.summary()).append("\n\n");
                } else if (liveObs != null && "live_metadata_unsupported".equals(liveObs.type())) {
                    sb.append("is not yet supported for this database type.\n\n");
                } else {
                    sb.append("returned no results.\n\n");
                }
                sb.append("To populate cached metadata, run the Brain learning tasks for this connection.\n");
            }
        }

        QueryResult primaryResult = vaultSufficient ? null : context.getMemory("liveMetadataResult");
        return new AgentExecutionResult(
            null, plan.intent(), sb.toString().trim(), primaryResult,
            plan.summarize(), context.executedQueries(), context.toolsUsed(), confidence
        );
    }

    private AgentExecutionResult composeUniversal(AgentPlan plan, AgentExecutionContext context) {
        String message = context.getMemory("universalMessage");
        QueryResult primaryResult = context.getMemory("universalPrimaryResult");
        Double confidence = context.getMemory("universalConfidence");
        AnswerContract answerContract = context.getMemory("verifiedAnswerContract");
        List<AgentTaskResult> taskResults = context.taskResults();
        VerificationReport verificationReport = strongestVerificationReport(context.verificationReports());
        AgentTaskResult strongestCompletedTask = taskResults.stream()
            .filter(AgentTaskResult::completed)
            .filter(task -> task.primaryResult() != null || !task.executedQueries().isEmpty())
            .findFirst()
            .orElse(null);

        if (looksLikeGenericEvidenceBlocker(message) && strongestCompletedTask != null) {
            if (strongestCompletedTask.message() != null && !strongestCompletedTask.message().isBlank()) {
                message = strongestCompletedTask.message();
            }
            if (primaryResult == null) {
                primaryResult = strongestCompletedTask.primaryResult();
            }
            if (confidence == null) {
                confidence = strongestCompletedTask.confidence();
            }
        }

        if ((message == null || message.isBlank()) && !taskResults.isEmpty()) {
            message = taskResults.stream()
                .map(AgentTaskResult::message)
                .filter(Objects::nonNull)
                .filter(msg -> !msg.isBlank())
                .findFirst()
                .orElse(null);
        }

        if (primaryResult == null) {
            primaryResult = taskResults.stream()
                .map(AgentTaskResult::primaryResult)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        }

        if (confidence == null && !taskResults.isEmpty()) {
            confidence = taskResults.stream()
                .mapToDouble(AgentTaskResult::confidence)
                .average()
                .orElse(0.55d);
        }

        if (message == null || message.isBlank()) {
            message = buildSourceExhaustionSummary(context);
        }

        if (answerContract == null) {
            answerContract = new AnswerContract(
                null,
                message,
                List.of(),
                List.of(),
                context.executedQueries().isEmpty() ? null : context.executedQueries().getFirst(),
                verificationReport != null ? verificationReport.notes() : List.of(),
                List.of(),
                null
            );
        } else if ((message == null || message.isBlank()) && answerContract.summary() != null) {
            message = answerContract.summary();
        }

        return new AgentExecutionResult(
            null,
            plan.intent(),
            message,
            primaryResult,
            plan.summarize(),
            context.executedQueries(),
            context.toolsUsed(),
            confidence != null ? confidence : 0.55d,
            taskResults,
            context.promptIntent(),
            answerContract,
            verificationReport
        );
    }

    private String buildSourceExhaustionSummary(AgentExecutionContext context) {
        EvidenceLedger ledger = EvidenceLedger.from(context);
        SourcePlan sourcePlan = context.getMemory("sourcePlan");
        List<String> attempted = ledger.attemptedSourceFamilies().stream().toList();
        List<String> planned = sourcePlan == null ? List.of() : sourcePlan.sourceFamilies();
        if (attempted.isEmpty() && planned.isEmpty()) {
            return "DeepSQL did not collect enough verified evidence to answer this run.";
        }
        StringBuilder sb = new StringBuilder("DeepSQL did not collect enough verified evidence to answer this run.\n\n");
        if (!attempted.isEmpty()) {
            sb.append("**Sources checked**: ").append(String.join(", ", attempted.stream().limit(8).toList())).append(".\n");
        }
        List<String> remaining = planned.stream()
            .filter(source -> attempted.stream().noneMatch(attemptedSource -> attemptedSource.equalsIgnoreCase(source)))
            .limit(6)
            .toList();
        if (!remaining.isEmpty()) {
            sb.append("**Sources still needed**: ").append(String.join(", ", remaining)).append(".\n");
        }
        return sb.toString().trim();
    }

    private VerificationReport strongestVerificationReport(List<VerificationReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return null;
        }
        return reports.stream()
            .filter(Objects::nonNull)
            .filter(report -> report.passed() || report.verifiedInsufficiency())
            .reduce((first, second) -> second)
            .orElseGet(() -> reports.stream().filter(Objects::nonNull).reduce((first, second) -> second).orElse(null));
    }

    private boolean looksLikeGenericEvidenceBlocker(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("need more evidence")
            || lower.startsWith("need additional query evidence")
            || lower.startsWith("need the underlying executed query")
            || lower.startsWith("need more work");
    }

    private String formatVaultColumnLine(Map<?, ?> row) {
        StringBuilder attributes = new StringBuilder();
        if (Boolean.TRUE.equals(row.get("primaryKey"))) {
            attributes.append("primary key");
        }
        Object nullable = row.get("nullable");
        if (nullable != null) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append(Boolean.TRUE.equals(nullable) ? "nullable" : "not null");
        }
        return "`" + Objects.toString(row.get("column"), "?") + "` — `"
            + Objects.toString(row.get("type"), "?") + "`"
            + (attributes.length() > 0 ? "; " + attributes : "");
    }

    private String formatVaultKeyColumnLine(Map<?, ?> row) {
        Object roles = row.get("roles");
        String detail;
        if (roles instanceof List<?> roleList && !roleList.isEmpty()) {
            detail = roleList.stream().map(Object::toString).reduce((a, b) -> a + "; " + b).orElse("-");
        } else {
            detail = Objects.toString(row.get("summary"), "-");
        }
        return "`" + Objects.toString(row.get("column"), "?") + "` — " + detail;
    }

    private String formatVaultIndexLine(Map<?, ?> row) {
        StringBuilder attributes = new StringBuilder();
        if (Boolean.TRUE.equals(row.get("unique"))) {
            attributes.append("unique");
        }
        if (row.get("type") != null && !Objects.toString(row.get("type"), "").isBlank()) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append(Objects.toString(row.get("type"), "").toLowerCase(Locale.ROOT));
        }
        return "`" + Objects.toString(row.get("index"), "?") + "` — columns: "
            + Objects.toString(row.get("columns"), "unspecified columns")
            + (attributes.length() > 0 ? "; " + attributes : "");
    }

    private String formatLiveIndexLine(List<Object> row) {
        Object indexName = row.size() > 0 ? row.get(0) : "";
        Object columnsValue = row.size() > 1 ? row.get(1) : "";
        Object uniqueValue = row.size() > 2 ? row.get(2) : "";
        Object typeValue = row.size() > 3 ? row.get(3) : "";
        StringBuilder attributes = new StringBuilder();
        if (Objects.toString(uniqueValue, "").equalsIgnoreCase("yes")
            || Objects.toString(uniqueValue, "").equalsIgnoreCase("true")) {
            attributes.append("unique");
        }
        if (typeValue != null && !Objects.toString(typeValue, "").isBlank()) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append(Objects.toString(typeValue, "").toLowerCase(Locale.ROOT));
        }
        return "`" + Objects.toString(indexName, "?") + "` — columns: "
            + Objects.toString(columnsValue, "unspecified columns")
            + (attributes.length() > 0 ? "; " + attributes : "");
    }

    private String formatLiveColumnLine(List<Object> row) {
        Object columnName = row.size() > 0 ? row.get(0) : "?";
        Object type = row.size() > 1 ? row.get(1) : "?";
        Object nullable = row.size() > 2 ? row.get(2) : null;
        String nullableText = nullable == null || Objects.toString(nullable).isBlank()
            ? ""
            : "; " + (Objects.toString(nullable).equalsIgnoreCase("YES")
                || Objects.toString(nullable).equalsIgnoreCase("true")
                ? "nullable"
                : "not null");
        return "`" + Objects.toString(columnName, "?") + "` — `"
            + Objects.toString(type, "?") + "`" + nullableText;
    }

    private String humanizeTopic(String observationType) {
        if (observationType == null) return "Metadata Analysis";
        return switch (observationType) {
            case "vault_relationships" -> "Table Relationships";
            case "vault_key_columns" -> "Key Column Analysis";
            case "vault_performance" -> "Performance Insights";
            case "vault_growth" -> "Growth Analysis";
            case "vault_classification" -> "Schema Classification";
            case "vault_workload" -> "Workload Profile";
            case "vault_tuning" -> "Tuning Recommendations";
            case "vault_general" -> "Schema Metadata Analysis";
            default -> "Metadata Analysis";
        };
    }

}
