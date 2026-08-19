package com.dbaagent.service;

import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.QueryExecutionProvider;
import com.dbaagent.util.QueryNormalizer;
import com.dbaagent.util.SqlStatementSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.UseStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryExecutionPolicyService {

    private static final Pattern USE_ANY_PATTERN = Pattern.compile("^\\s*USE\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATEMENT_START_PATTERN = Pattern.compile(
        "^\\s*(SELECT|INSERT|UPDATE|DELETE|WITH|CREATE|ALTER|DROP|TRUNCATE|REPLACE|EXPLAIN|SHOW|CALL|EXEC|MERGE|UPSERT)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPLAIN_MUTATION_PATTERN = Pattern.compile(
        "^\\s*EXPLAIN\\b[\\s\\S]*\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|REPLACE|CREATE|ALTER|DROP|TRUNCATE|CALL|EXECUTE)\\b",
        Pattern.CASE_INSENSITIVE
    );
    // Matches "DROP TABLE [IF EXISTS] ..." (with optional leading whitespace) as a fallback for
    // classifier paths that cannot determine the dropped object type.
    private static final Pattern DROP_TABLE_PATTERN = Pattern.compile(
        "^\\s*DROP\\s+(?:IF\\s+EXISTS\\s+)?TABLE\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Text-level backstops for writes that hide inside a statement which reads as
    // a SELECT. Applied to SQL with literals and comments already stripped.
    private static final Pattern CTE_WRITE_PATTERN = Pattern.compile(
        "\\bAS\\s*\\(\\s*(INSERT|UPDATE|DELETE|MERGE)\\b",
        Pattern.CASE_INSENSITIVE
    );
    // Anchored on INTO alone, with no leading wildcard. The previous form
    // (\bSELECT\b[\s\S]*?\bINTO ...) backtracked quadratically: 224KB of
    // repeated "SELECT " took ~44s of CPU in the guard before the query reached
    // the database. The caller already knows the statement is a SELECT, so the
    // prefix bought nothing.
    private static final Pattern SELECT_INTO_PATTERN = Pattern.compile(
        "\\bINTO\\s+(?!STRICT\\b)[\"`\\w]",
        Pattern.CASE_INSENSITIVE
    );

    private final DatabaseProviderRegistry providerRegistry;

    public PolicyDecision enforce(
        QueryRequest request,
        QueryExecutionContext context,
        String dbType
    ) {
        QueryExecutionContext effectiveContext = context == null ? QueryExecutionContext.internal() : context;
        QueryExecutionOrigin origin = QueryExecutionOrigin.normalized(effectiveContext.origin());
        QueryExecutionProvider executionProvider = providerRegistry.getDialect(dbType).queryExecution();

        String sql = request == null ? null : request.getQuery();
        String canonicalDbType = providerRegistry.getCanonicalName(dbType);
        boolean hashIsComment = !"postgres".equalsIgnoreCase(canonicalDbType);
        List<String> statements = SqlStatementSplitter.split(sql, hashIsComment);
        if (statements.isEmpty()) {
            statements = List.of(sql == null ? "" : sql);
        }

        List<StatementClassification> classifications = new ArrayList<>();
        for (String statement : statements) {
            classifications.add(classifyStatement(statement, executionProvider));
        }

        boolean anyMutation = classifications.stream().anyMatch(StatementClassification::mutating);
        boolean allReadOnlyOrPreamble = classifications.stream().allMatch(c -> c.readOnly || c.sessionPreamble);
        String primaryQueryType = classifications.stream()
            .filter(c -> !c.sessionPreamble)
            .map(StatementClassification::queryType)
            .findFirst()
            .orElse("UNKNOWN");

        // When the splitter found no semicolons (single-entry list) but the text looks like
        // multiple statements, the user forgot separators — give them a specific error instead
        // of a confusing "DDL/DML forbidden" message.
        if (statements.size() == 1 && !allReadOnlyOrPreamble && looksLikeMultipleStatements(statements.getFirst())) {
            throw QueryExecutionPolicyException.multiStatementMissingSemicolons();
        }

        if (effectiveContext.mutationMode() == QueryExecutionContext.MutationMode.READ_ONLY_ONLY) {
            if (!allReadOnlyOrPreamble || anyMutation) {
                if (origin == QueryExecutionOrigin.CHAT) {
                    throw QueryExecutionPolicyException.chatReadOnly(primaryQueryType);
                }
                throw QueryExecutionPolicyException.editorMutationForbidden(primaryQueryType);
            }
            return new PolicyDecision(classifications, false, primaryQueryType);
        }

        if (!anyMutation) {
            return new PolicyDecision(classifications, false, primaryQueryType);
        }

        if (statements.size() > 1) {
            throw QueryExecutionPolicyException.unsafeMutation(
                "DeepSQL only allows a single DDL or DML statement per Editor run. Mixed multi-statement mutation batches are blocked in v1.",
                primaryQueryType
            );
        }

        StatementClassification mutation = classifications.getFirst();
        if (!effectiveContext.actorIsAdmin()) {
            throw QueryExecutionPolicyException.editorMutationForbidden(mutation.queryType());
        }

        if (origin == QueryExecutionOrigin.EDITOR
            && isDropTableStatement(mutation.queryType(), statements.getFirst())) {
            throw QueryExecutionPolicyException.unsafeMutation(
                "DROP TABLE is not allowed from the SQL Editor. Use your database admin tooling to remove tables. "
                    + "Other DROP statements (INDEX, VIEW, SEQUENCE, etc.) are permitted with confirmation.",
                mutation.queryType()
            );
        }

        if (mutation.requiresWhereClause && !mutation.hasWhereClause) {
            throw QueryExecutionPolicyException.unsafeMutation(
                "UPDATE or DELETE without a WHERE clause is blocked in the SQL Editor. Add a WHERE clause to limit the affected rows.",
                mutation.queryType()
            );
        }

        if (!effectiveContext.mutationConfirmed()) {
            throw QueryExecutionPolicyException.confirmationRequired(mutation.queryType(), buildWarnings(mutation));
        }

        return new PolicyDecision(classifications, true, mutation.queryType());
    }

    private List<String> buildWarnings(StatementClassification mutation) {
        List<String> warnings = new ArrayList<>();
        warnings.add("DeepSQL will submit this statement directly to the selected database connection.");
        warnings.add("The database will enforce whether that connection user has the required write privileges.");
        if (mutation.requiresWhereClause) {
            warnings.add("A WHERE clause was detected, but you should still verify the target rows before you confirm.");
        } else {
            warnings.add("DDL changes can be irreversible. Confirm only after you validate the exact object names and impact.");
        }
        return warnings;
    }

    private StatementClassification classifyStatement(String statement, QueryExecutionProvider executionProvider) {
        String trimmed = statement == null ? "" : statement.trim();
        if (trimmed.isBlank()) {
            return new StatementClassification("UNKNOWN", false, false, false, false, false);
        }
        if (SqlStatementSplitter.isSetStatement(trimmed)) {
            return new StatementClassification("SET", false, false, false, false, true);
        }
        if (isUseStatement(trimmed)) {
            return new StatementClassification("USE", false, false, false, false, true);
        }

        // A pre-parse scan runs first so a statement the parser mis-models — or
        // rejects outright — still fails closed. `WITH ... DELETE` parses cleanly
        // as a Select, and the keyword fallback below treats anything starting
        // with WITH as read-only, so without this a write reaches the database
        // classified as a read.
        String hiddenWrite = detectHiddenWrite(trimmed);

        try {
            Statement parsed = CCJSqlParserUtil.parse(trimmed);
            if (parsed instanceof Select select) {
                String writeKind = hiddenWrite != null ? hiddenWrite : detectSelectWrite(select);
                if (writeKind != null) {
                    // requiresWhereClause=false: the WHERE guard reads the top-level
                    // statement, which is a SELECT here. A data-modifying CTE always
                    // needs explicit confirmation instead.
                    return new StatementClassification(writeKind, false, true, false, true, false);
                }
                return new StatementClassification("SELECT", true, false, false, false, false);
            }
            if (parsed instanceof ExplainStatement) {
                return new StatementClassification("EXPLAIN", true, false, false, false, false);
            }
            if (parsed instanceof UseStatement) {
                return new StatementClassification("USE", false, false, false, false, true);
            }
            if (parsed instanceof Insert) {
                return new StatementClassification("INSERT", false, true, false, true, false);
            }
            if (parsed instanceof Update update) {
                return new StatementClassification("UPDATE", false, true, true, update.getWhere() != null, false);
            }
            if (parsed instanceof Delete delete) {
                return new StatementClassification("DELETE", false, true, true, delete.getWhere() != null, false);
            }
            if (parsed instanceof Merge) {
                return new StatementClassification("MERGE", false, true, false, true, false);
            }
            if (parsed instanceof Upsert) {
                return new StatementClassification("UPSERT", false, true, false, true, false);
            }
            if (parsed instanceof CreateTable) {
                return new StatementClassification("CREATE", false, true, false, true, false);
            }
            if (parsed instanceof CreateIndex) {
                return new StatementClassification("CREATE INDEX", false, true, false, true, false);
            }
            if (parsed instanceof Alter) {
                return new StatementClassification("ALTER", false, true, false, true, false);
            }
            if (parsed instanceof Drop drop) {
                String objectType = drop.getType();
                String queryType = (objectType == null || objectType.isBlank())
                    ? "DROP"
                    : "DROP " + objectType.toUpperCase(Locale.ROOT);
                return new StatementClassification(queryType, false, true, false, true, false);
            }
            if (parsed instanceof Truncate) {
                return new StatementClassification("TRUNCATE", false, true, false, true, false);
            }
        } catch (Exception parseError) {
            log.debug("Falling back to keyword SQL classification: {}", parseError.getMessage());
        }

        String explainWrappedMutation = detectExplainWrappedMutation(trimmed);
        if (explainWrappedMutation != null) {
            boolean requiresWhere = "UPDATE".equalsIgnoreCase(explainWrappedMutation)
                || "DELETE".equalsIgnoreCase(explainWrappedMutation);
            boolean hasWhere = !requiresWhere || containsWhereClause(trimmed);
            return new StatementClassification(explainWrappedMutation, false, true, requiresWhere, hasWhere, false);
        }

        // The parser failed or produced a type we don't model. `isReadOnlyQuery`
        // only looks at the leading keyword, so a hidden write must veto it.
        if (hiddenWrite != null) {
            return new StatementClassification(hiddenWrite, false, true, false, true, false);
        }

        String queryType = QueryNormalizer.detectQueryType(trimmed);
        boolean readOnly = executionProvider.isReadOnlyQuery(trimmed);
        boolean mutating = !readOnly && !"UNKNOWN".equalsIgnoreCase(queryType);
        boolean requiresWhere = "UPDATE".equalsIgnoreCase(queryType) || "DELETE".equalsIgnoreCase(queryType);
        boolean hasWhere = !requiresWhere || containsWhereClause(trimmed);
        return new StatementClassification(queryType, readOnly, mutating, requiresWhere, hasWhere, false);
    }

    /**
     * Detects a write hidden inside a statement that parses as a {@link Select}:
     * a data-modifying CTE ({@code WITH x AS (DELETE ...) SELECT ...}, which
     * PostgreSQL executes for real) or {@code SELECT ... INTO newtable}, which
     * creates a table. Returns the write's query type, or null when the select
     * really is read-only.
     */
    private String detectSelectWrite(Select select) {
        String fromCte = detectWriteInWithItems(select.getWithItemsList(), 0);
        if (fromCte != null) {
            return fromCte;
        }
        return detectSelectInto(select);
    }

    private String detectWriteInWithItems(List<WithItem<?>> withItems, int depth) {
        // CTEs can nest; bound the walk so a pathological query can't spin here.
        if (withItems == null || withItems.isEmpty() || depth > 10) {
            return null;
        }
        for (WithItem<?> item : withItems) {
            if (item == null) {
                continue;
            }
            if (item.getDelete() != null) return "DELETE (in CTE)";
            if (item.getUpdate() != null) return "UPDATE (in CTE)";
            if (item.getInsert() != null) return "INSERT (in CTE)";

            // getSelect() throws when the item holds a non-select statement, which
            // the getters above already covered. A select here may carry its own
            // WITH list, so recurse to catch a write nested one level deeper.
            try {
                Select inner = item.getSelect();
                if (inner != null) {
                    String innerWrite = detectSelectWriteQuietly(inner, depth + 1);
                    if (innerWrite != null) {
                        return innerWrite;
                    }
                }
            } catch (Exception ignored) {
                // Not a select-bearing CTE; nothing further to inspect.
            }
        }
        return null;
    }

    private String detectSelectWriteQuietly(Select select, int depth) {
        if (depth > 10) {
            return null;
        }
        String fromCte = detectWriteInWithItems(select.getWithItemsList(), depth);
        return fromCte != null ? fromCte : detectSelectInto(select);
    }

    private String detectSelectInto(Select select) {
        if (select instanceof PlainSelect plain) {
            List<Table> into = plain.getIntoTables();
            if (into != null && !into.isEmpty()) {
                return "SELECT INTO";
            }
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            Select inner = parenthesed.getSelect();
            return inner == null ? null : detectSelectInto(inner);
        }
        if (select instanceof SetOperationList setOps && setOps.getSelects() != null) {
            for (Select part : setOps.getSelects()) {
                String found = detectSelectInto(part);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Text-level backstop for the same two shapes, used when the parser is not
     * available or disagrees. Quoted literals and comments are stripped first so
     * the word DELETE inside a string can't trigger a false positive.
     */
    private String detectHiddenWrite(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String scrubbed = stripLeadingComments(stripComments(stripQuotedLiterals(sql)));
        boolean startsWithWith = scrubbed.regionMatches(true, 0, "WITH", 0, 4);
        if (startsWithWith && CTE_WRITE_PATTERN.matcher(scrubbed).find()) {
            return "WRITE (in CTE)";
        }
        // SELECT_INTO_PATTERN matches a bare INTO target, so it must only be
        // consulted for statements that actually read as a SELECT — otherwise
        // "INSERT INTO t SELECT ..." would be relabelled SELECT INTO.
        if (startsWithWith || scrubbed.regionMatches(true, 0, "SELECT", 0, 6)) {
            return SELECT_INTO_PATTERN.matcher(scrubbed).find() ? "SELECT INTO" : null;
        }
        return null;
    }

    // Removes block and line comments with a single linear scan. The regex form
    // matched a lazy wildcard between block-comment delimiters and backtracked
    // quadratically on an unterminated block comment: 96KB of input cost ~14s
    // of CPU.
    private String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
                out.append(' ');
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i + 2);
                i = nl < 0 ? len : nl;
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private boolean isUseStatement(String sql) {
        return sql != null && USE_ANY_PATTERN.matcher(stripLeadingComments(sql)).find();
    }

    /**
     * Returns true only for {@code DROP TABLE}. Other DROP variants (INDEX, VIEW, SEQUENCE,
     * SCHEMA, FUNCTION, PROCEDURE, …) are permitted as ordinary mutations for confirmed admins.
     *
     * <p>The {@link Drop} parser populates the object type, so {@code queryType} will normally be
     * something like "DROP TABLE" or "DROP INDEX". When classification fell back to keyword
     * matching it may be just "DROP"; in that case we scan the raw SQL with a strict regex so we
     * don't accidentally let a DROP TABLE slip through.
     */
    private boolean isDropTableStatement(String queryType, String sql) {
        if (queryType != null) {
            String upper = queryType.toUpperCase(Locale.ROOT);
            if (upper.equals("DROP TABLE")) {
                return true;
            }
            if (upper.startsWith("DROP ")) {
                // Object type is known and it is not TABLE → allow.
                return false;
            }
        }
        // queryType is null or plain "DROP" (object type unknown). Inspect the raw SQL.
        String stripped = stripLeadingComments(sql == null ? "" : sql);
        return DROP_TABLE_PATTERN.matcher(stripped).find();
    }

    private String detectExplainWrappedMutation(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String stripped = stripQuotedLiterals(sql);
        if (!EXPLAIN_MUTATION_PATTERN.matcher(stripped).find()) {
            return null;
        }
        String upper = stripped.toUpperCase(Locale.ROOT);
        for (String keyword : List.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "CREATE", "ALTER", "DROP", "TRUNCATE", "CALL", "EXECUTE")) {
            if (upper.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private String stripLeadingComments(String sql) {
        String remaining = sql == null ? "" : sql.trim();
        while (!remaining.isEmpty()) {
            if (remaining.startsWith("--") || remaining.startsWith("#")) {
                int newline = remaining.indexOf('\n');
                remaining = newline >= 0 ? remaining.substring(newline + 1).trim() : "";
                continue;
            }
            if (remaining.startsWith("/*")) {
                int end = remaining.indexOf("*/");
                remaining = end >= 0 ? remaining.substring(end + 2).trim() : "";
                continue;
            }
            break;
        }
        return remaining;
    }

    private String stripQuotedLiterals(String sql) {
        // The alternations are ordered so no input has two parses: '' must be
        // tried before the single-char branch, and the char class excludes both
        // quote and backslash. Without that the ('' | [^'\\])* form is ambiguous
        // and backtracks exponentially (java/polynomial-redos) on a long
        // run of quotes — reachable from Editor SQL, so this is on a hot path.
        return sql
            .replaceAll("'(?:''|\\\\.|[^'\\\\])*+'", "''")
            .replaceAll("\"(?:\\\\.|[^\"\\\\])*+\"", "\"\"");
    }

    private boolean containsWhereClause(String sql) {
        return sql != null && sql.toUpperCase(Locale.ROOT).contains(" WHERE ");
    }

    private boolean looksLikeMultipleStatements(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String stripped = stripLeadingComments(stripQuotedLiterals(sql));
        long statementStarts = stripped.lines()
            .filter(line -> STATEMENT_START_PATTERN.matcher(line).find())
            .count();
        return statementStarts > 1;
    }

    public record PolicyDecision(
        List<StatementClassification> classifications,
        boolean mutating,
        String primaryQueryType
    ) {
    }

    public record StatementClassification(
        String queryType,
        boolean readOnly,
        boolean mutating,
        boolean requiresWhereClause,
        boolean hasWhereClause,
        boolean sessionPreamble
    ) {
    }
}
