package com.dbaagent.service;

import com.dbaagent.model.Permission;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.QueryExecutionProvider;
import com.dbaagent.service.security.AccessControlService;
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
    // MCP/coding-agent loops may not run any DROP or TRUNCATE, including
    // DROP INDEX / DROP VIEW. EXPLAIN wrappers are stripped before matching.
    private static final Pattern DROP_OR_TRUNCATE_HEAD = Pattern.compile(
        "^\\s*(DROP|TRUNCATE)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPLAIN_PREFIX_PATTERN = Pattern.compile(
        "^\\s*EXPLAIN(?:\\s*\\([^)]*\\)|\\s+ANALYZE)?\\s+",
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

    // Server administration. Blocked on every surface for every role, confirmed or
    // not: none of it is data work, and each one can take a production database down
    // or widen access (users/grants, KILL, LOCK TABLES, FLUSH ... READ LOCK,
    // replication and binlog control). Matched on the leading verb only, after
    // comments and literals are stripped, so a `grant_id` column is fine.
    private static final Pattern ADMIN_STATEMENT_PATTERN = Pattern.compile(
        "^(?:GRANT|REVOKE|KILL|SHUTDOWN|FLUSH|INSTALL|UNINSTALL|PURGE|REASSIGN"
            + "|LOCK\\s+(?:TABLES?|INSTANCE)|UNLOCK\\s+INSTANCE"
            + "|(?:CREATE|ALTER|DROP|RENAME)\\s+(?:USER|ROLE)|ALTER\\s+SYSTEM"
            + "|RESET\\s+(?:MASTER|REPLICA|SLAVE|BINARY|PERSIST)"
            + "|CHANGE\\s+(?:MASTER|REPLICATION)|(?:START|STOP)\\s+(?:SLAVE|REPLICA|GROUP_REPLICATION)"
            + "|SET\\s+(?:PASSWORD|ROLE|DEFAULT\\s+ROLE))\\b",
        Pattern.CASE_INSENSITIVE
    );
    // Any SET that reaches global or persisted scope, anywhere in its assignment list
    // (`SET @x = 1, GLOBAL max_connections = 10` is valid MySQL), or that turns off
    // binary logging for the session — replicas would silently diverge.
    private static final Pattern SET_PREFIX_PATTERN = Pattern.compile("^SET\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_BEYOND_SESSION_PATTERN = Pattern.compile(
        "\\b(?:GLOBAL|PERSIST|PERSIST_ONLY)\\b|@@(?:GLOBAL|PERSIST|PERSIST_ONLY)\\.|\\bsql_log_bin\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DDL_VERB_PATTERN = Pattern.compile(
        "^(?:CREATE|ALTER|DROP|RENAME|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE);
    // MySQL executes the body of /*! ... */ (and /*!50000 ... */), while every check in
    // this class strips it as a comment and then sees an empty statement that
    // isReadOnlyQuery calls read-only — so `/*!50000 GRANT ... */` skipped every guard.
    // /*+ ... */ optimizer hints (MAX_EXECUTION_TIME, SET_VAR) override the session
    // guards for one statement. Matched on the raw text: a literal containing "/*!" is
    // refused too, which is the right side to be wrong on.
    // MariaDB's /*M! ... */ is executable there and inert on MySQL; a "mysql" connection
    // can point at either. \s* over-blocks `/* !`, which is harmless.
    private static final Pattern EXECUTABLE_COMMENT_PATTERN =
        Pattern.compile("/\\*\\s*(?:M?!|\\+)", Pattern.CASE_INSENSITIVE);
    // Mutations that only change rows. Anything else that mutates — DDL, CALL/DO (a
    // procedure can run DDL), SELECT ... INTO a new table — needs EXECUTE_DDL. An
    // allowlist, so a statement shape nobody anticipated lands on the stricter side.
    // "WRITE" is the text backstop's label for a CTE matching AS ( INSERT|UPDATE|DELETE|MERGE,
    // which is always a row write; the leading-verb check below still has to agree.
    private static final java.util.Set<String> ROW_ONLY_MUTATIONS =
        java.util.Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "WRITE");
    // The statement's real leading verb must agree. queryType alone can come from the
    // keyword fallback, which sanitizes a prefix before matching, so it can disagree with
    // what the database executes. WITH covers data-modifying CTEs. EXPLAIN is absent on
    // purpose: Postgres runs `EXPLAIN ANALYZE CREATE TABLE ... AS` for real.
    private static final Pattern ROW_ONLY_LEADING_VERB = Pattern.compile(
        "^(?:INSERT|UPDATE|DELETE|MERGE|UPSERT|REPLACE|WITH)\\b", Pattern.CASE_INSENSITIVE);

    private final DatabaseProviderRegistry providerRegistry;
    private final AccessControlService accessControlService;

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

        // Before classification: SET used to be waved through as a session preamble,
        // which let SET GLOBAL run for any role, read-only ones included.
        for (String statement : statements) {
            if (statement != null && EXECUTABLE_COMMENT_PATTERN.matcher(statement).find()) {
                throw QueryExecutionPolicyException.unsafeMutation(
                    "DeepSQL does not run MySQL executable comments (/*! ... */) or optimizer hints (/*+ ... */): "
                        + "the database runs what is inside them, but DeepSQL's safety checks cannot see it. "
                        + "Write the statement without them.",
                    "UNKNOWN"
                );
            }
            if (isAdministrativeStatement(statement)) {
                throw QueryExecutionPolicyException.adminStatementBlocked();
            }
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

        // Same idea, one step earlier: a statement DeepSQL could not parse is blocked for
        // everyone, but it is a syntax error, not DDL/DML. Reported ahead of both the
        // read-only branch and the mutation-confirmation branch, so an admin gets the same
        // accurate diagnosis instead of being offered a confirmation prompt for a
        // statement nothing has actually classified.
        StatementClassification unparseable = classifications.stream()
            .filter(StatementClassification::notParseable)
            .findFirst()
            .orElse(null);
        if (unparseable != null) {
            throw QueryExecutionPolicyException.statementNotParseable(unparseable.queryType());
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
        if (!effectiveContext.actorMayMutate()) {
            throw QueryExecutionPolicyException.editorMutationForbidden(mutation.queryType());
        }

        if (origin == QueryExecutionOrigin.MCP
            && isDropOrTruncateStatement(mutation.queryType(), statements.getFirst())) {
            throw QueryExecutionPolicyException.unsafeMutation(
                "DROP and TRUNCATE are blocked on MCP and coding-agent loops. "
                    + "CREATE, ALTER, and DML still require admin or DBA privileges plus confirmation.",
                mutation.queryType()
            );
        }

        if (origin == QueryExecutionOrigin.EDITOR
            && isDropTableStatement(mutation.queryType(), statements.getFirst())) {
            throw QueryExecutionPolicyException.unsafeMutation(
                "DROP TABLE is not allowed from the SQL Editor. Use your database admin tooling to remove tables. "
                    + "Other DROP statements (INDEX, VIEW, SEQUENCE, etc.) are permitted with confirmation.",
                mutation.queryType()
            );
        }

        if (origin == QueryExecutionOrigin.EDITOR && isTruncate(mutation.queryType(), statements.getFirst())) {
            throw QueryExecutionPolicyException.unsafeMutation(
                "TRUNCATE is not allowed from the SQL Editor: it empties the whole table and cannot be rolled back. "
                    + "Use DELETE with a WHERE clause, or your database admin tooling.",
                mutation.queryType()
            );
        }

        List<String> confirmationNotes = new ArrayList<>();
        boolean ddl = isDdlStatement(statements.getFirst(), mutation.queryType());
        if (ddl && (origin == QueryExecutionOrigin.EDITOR || origin == QueryExecutionOrigin.MCP)) {
            if (!accessControlService.hasPermission(Permission.EXECUTE_DDL)) {
                throw QueryExecutionPolicyException.ddlPermissionRequired(mutation.queryType());
            }
            // Refuse a blocking ALTER here, ahead of the confirmation prompt, rather than
            // let someone confirm a statement the executor will then reject.
            List<String> attempts;
            try {
                attempts = executionProvider.onlineDdlAttempts(statements.getFirst());
            } catch (IllegalArgumentException blocking) {
                throw QueryExecutionPolicyException.unsafeMutation(blocking.getMessage(), mutation.queryType());
            }
            if (!attempts.equals(List.of(statements.getFirst()))) {
                confirmationNotes.add("DeepSQL will run this online: ALGORITHM=INSTANT first, then ALGORITHM=INPLACE "
                    + "with LOCK=NONE. If neither can run without blocking writes, it stops and changes nothing.");
            }
            confirmationNotes.add("If the table is busy, DeepSQL gives up after waiting 5 seconds for its lock "
                + "instead of queueing every other write to the table behind this change.");
        }

        if (mutation.requiresWhereClause && !mutation.hasWhereClause) {
            throw QueryExecutionPolicyException.unsafeMutation(
                "UPDATE or DELETE without a WHERE clause is blocked in the SQL Editor. Add a WHERE clause to limit the affected rows.",
                mutation.queryType()
            );
        }

        if (!effectiveContext.mutationConfirmed()) {
            List<String> warnings = new ArrayList<>(buildWarnings(mutation));
            warnings.addAll(confirmationNotes);
            throw QueryExecutionPolicyException.confirmationRequired(mutation.queryType(), warnings);
        }

        return new PolicyDecision(classifications, true, mutation.queryType());
    }

    /**
     * Deliberately reads the raw text, without stripping string literals. Stripping is a
     * second tokenizer that can disagree with MySQL's — `SET @a = "'", GLOBAL x = 1, @b = "'"`
     * fooled a two-pass regex, and a NO_BACKSLASH_ESCAPES preamble changes MySQL's own
     * tokenizing — so the check fails closed: `SET @x = 'global'` is refused too.
     */
    private boolean isAdministrativeStatement(String statement) {
        String head = stripLeadingComments(statement).trim();
        if (ADMIN_STATEMENT_PATTERN.matcher(head).find()) {
            return true;
        }
        return SET_PREFIX_PATTERN.matcher(head).find() && SET_BEYOND_SESSION_PATTERN.matcher(head).find();
    }

    /**
     * A leading DDL verb, or any mutation that is not row-only DML. The verb check alone
     * missed CALL (a procedure can run DDL) and Postgres SELECT ... INTO, which creates a
     * table; the keyword fallback also reports RENAME as "OTHER".
     */
    private boolean isDdlStatement(String statement, String queryType) {
        String head = stripLeadingComments(statement);
        if (DDL_VERB_PATTERN.matcher(head).find()) {
            return true;
        }
        // Data-modifying CTEs are labelled "DELETE (in CTE)" etc.; compare the verb.
        String verb = queryType == null ? "" : queryType.trim().split("[\\s(]", 2)[0].toUpperCase(Locale.ROOT);
        boolean rowOnlyType = ROW_ONLY_MUTATIONS.contains(verb);
        return !(rowOnlyType && ROW_ONLY_LEADING_VERB.matcher(head).find());
    }

    private boolean isTruncate(String queryType, String statement) {
        return (queryType != null && queryType.toUpperCase(Locale.ROOT).startsWith("TRUNCATE"))
            || stripLeadingComments(statement).toUpperCase(Locale.ROOT).startsWith("TRUNCATE");
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

        boolean parseFailed = false;
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
                String wrappedMutation = detectExplainWrappedMutation(trimmed);
                if (wrappedMutation != null) {
                    boolean requiresWhere = "UPDATE".equalsIgnoreCase(wrappedMutation)
                        || "DELETE".equalsIgnoreCase(wrappedMutation);
                    boolean hasWhere = !requiresWhere || containsWhereClause(trimmed);
                    return new StatementClassification(
                        wrappedMutation, false, true, requiresWhere, hasWhere, false
                    );
                }
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
            parseFailed = true;
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

        // The two keyword heuristics can contradict each other, and their disagreement
        // means "malformed", not "mutation". `QueryNormalizer.detectQueryType` sanitizes a
        // prefix away before matching, so it answers SELECT for `"select ...`; the
        // provider's `isReadOnlyQuery` strips only comments, still sees the leading quote,
        // and answers false. That combination used to fall through as mutating=true, and a
        // user pasting a SELECT with the double quotes it carried in source code was told
        // "Only admins or DBAs can execute DDL or DML" — a permissions error for a syntax problem.
        //
        // It stays blocked: the parser rejected it, so nothing here can vouch for it being
        // read-only, and this is deliberately reported the same way to admins rather than
        // routed into the mutation-confirmation flow. Only the diagnosis changes.
        if (parseFailed && mutating && hiddenWrite == null && isReadOnlyVerb(queryType)) {
            return new StatementClassification(queryType, false, false, false, false, false, true);
        }

        return new StatementClassification(queryType, readOnly, mutating, requiresWhere, hasWhere, false);
    }

    /**
     * True for the statement verbs that never write. Narrow on purpose: it gates the
     * "malformed, not a mutation" reclassification above, and a write verb the parser
     * happens to reject (a Postgres DDL form JSqlParser does not model, say) must keep its
     * existing mutation handling rather than be re-labelled a syntax error.
     */
    private boolean isReadOnlyVerb(String queryType) {
        if (queryType == null) {
            return false;
        }
        return switch (queryType.toUpperCase(Locale.ROOT)) {
            case "SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN" -> true;
            default -> false;
        };
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
     * True for any {@code DROP …} or {@code TRUNCATE …}, including EXPLAIN-wrapped forms.
     * Used by the MCP origin gate so coding agents cannot confirm around destructive DDL.
     */
    private boolean isDropOrTruncateStatement(String queryType, String sql) {
        if (queryType != null) {
            String upper = queryType.toUpperCase(Locale.ROOT);
            if (upper.startsWith("DROP") || upper.startsWith("TRUNCATE")) {
                return true;
            }
        }
        String remaining = stripLeadingComments(sql == null ? "" : sql);
        remaining = EXPLAIN_PREFIX_PATTERN.matcher(remaining).replaceFirst("");
        remaining = stripLeadingComments(remaining);
        return DROP_OR_TRUNCATE_HEAD.matcher(remaining).find();
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
        return sql
            .replaceAll("'([^'\\\\]|\\\\.|'')*'", "''")
            .replaceAll("\"([^\"\\\\]|\\\\.)*\"", "\"\"");
    }

    // Word-bounded and comment-stripped: the previous literal " WHERE " match
    // missed a WHERE preceded by a newline (as in any multi-line formatted
    // UPDATE/DELETE) and was satisfied by a commented-out "-- WHERE ..." that
    // never reaches the database. This only runs on the keyword-fallback path —
    // when JSqlParser succeeds, delete.getWhere()/update.getWhere() are used
    // instead, which are exact.
    private static final Pattern WHERE_CLAUSE_PATTERN = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    private boolean containsWhereClause(String sql) {
        if (sql == null) {
            return false;
        }
        return WHERE_CLAUSE_PATTERN.matcher(stripComments(sql)).find();
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
        boolean sessionPreamble,
        boolean notParseable
    ) {
        public StatementClassification(
            String queryType,
            boolean readOnly,
            boolean mutating,
            boolean requiresWhereClause,
            boolean hasWhereClause,
            boolean sessionPreamble
        ) {
            this(queryType, readOnly, mutating, requiresWhereClause, hasWhereClause, sessionPreamble, false);
        }
    }
}
