package com.dbaagent.service.migration;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class DdlStatementParser {

    private static final Pattern CONCURRENTLY =
            Pattern.compile("(?i)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\b");
    private static final Pattern DEFAULT_FN =
            Pattern.compile("(?i)DEFAULT\\s+([a-z_][a-z0-9_]*)\\s*\\(");
    private static final Pattern REFERENCES =
            Pattern.compile("(?i)REFERENCES\\s+([a-z_][a-z0-9_.\"]*)\\s*\\(");
    private static final Pattern VALIDATE_CONSTRAINT =
            Pattern.compile("(?i)^\\s*ALTER\\s+TABLE\\s+(\\S+)\\s+VALIDATE\\s+CONSTRAINT\\b");

    public Optional<DdlFacts> parse(String sql) {
        if (sql == null || sql.isBlank()) return Optional.empty();

        int notValidAt = notValidStart(sql);
        boolean notValid = notValidAt >= 0;
        String normalized = notValid ? sql.substring(0, notValidAt) : sql;

        boolean concurrently = CONCURRENTLY.matcher(normalized).find();
        if (concurrently) {
            normalized = CONCURRENTLY.matcher(normalized)
                    .replaceAll(m -> "CREATE " + (m.group(1) == null ? "" : m.group(1)) + "INDEX");
        }

        // VALIDATE CONSTRAINT degrades to UNSPECIFIC in JSqlParser; match it textually first.
        var vc = VALIDATE_CONSTRAINT.matcher(sql);
        if (vc.find()) {
            return Optional.of(new DdlFacts(DdlOperation.VALIDATE_CONSTRAINT, strip(vc.group(1)),
                    null, null, null, null, null, false, notValid, false, null, sql));
        }

        List<Statement> statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(normalized);
        } catch (Exception e) {
            return Optional.empty();
        }
        // Postgres executes every statement in the string, not just the first; a single
        // DdlFacts cannot honestly represent more than one, so refuse rather than judge
        // the string on its first statement alone.
        if (statements.size() != 1) return Optional.empty();
        Statement stmt = statements.get(0);

        if (stmt instanceof CreateIndex ci) {
            return Optional.of(new DdlFacts(DdlOperation.CREATE_INDEX,
                    strip(ci.getTable().getName()), null, null, null, null, null,
                    false, notValid, concurrently, null, sql));
        }
        if (stmt instanceof Alter alter) {
            return fromAlter(alter, sql, notValid);
        }
        return Optional.empty();
    }

    private Optional<DdlFacts> fromAlter(Alter alter, String rawSql, boolean notValid) {
        List<AlterExpression> exprs = alter.getAlterExpressions();
        if (exprs == null || exprs.isEmpty()) return Optional.empty();
        // A single DdlFacts cannot honestly represent more than one clause; refuse rather than
        // judge the statement on the first clause alone.
        if (exprs.size() > 1) return Optional.empty();
        AlterExpression e = exprs.get(0);
        String table = strip(alter.getTable().getName());
        String op = e.getOperation() == null ? "" : e.getOperation().name().toUpperCase(Locale.ROOT);

        // DROP CONSTRAINT arrives with the same operation=DROP and a null column name;
        // only a genuine DROP COLUMN names a column. A dropped constraint also takes a
        // lock on the referenced table, which this parser has no data for — fail closed.
        if ("DROP".equals(op) && e.getColumnName() != null) {
            return Optional.of(new DdlFacts(DdlOperation.DROP_COLUMN, table, strip(e.getColumnName()),
                    null, null, null, null, false, notValid, false, null, rawSql));
        }
        if ("RENAME".equals(op)) {
            // JSqlParser exposes only the NEW name here; the old name is not recoverable.
            return Optional.of(new DdlFacts(DdlOperation.RENAME_COLUMN, table, null,
                    strip(e.getColumnName()), null, null, null, false, notValid, false, null, rawSql));
        }
        if ("ADD".equals(op)) {
            if (e.getIndex() != null && e.getIndex().getType() != null
                    && e.getIndex().getType().toUpperCase(Locale.ROOT).contains("FOREIGN")) {
                return Optional.of(new DdlFacts(DdlOperation.ADD_FOREIGN_KEY, table, null, null, null,
                        null, null, false, notValid, false, referencedTable(rawSql), rawSql));
            }
            // A constraint clause (ADD CONSTRAINT ... CHECK / ADD CHECK) parses with a non-null
            // Index and no column data type; a plain ADD COLUMN never sets one. Checking a whole-
            // statement substring instead would misclassify an ordinary column named e.g. check_flag.
            if (e.getIndex() != null) {
                return Optional.of(new DdlFacts(DdlOperation.ADD_CHECK, table, null, null, null,
                        null, null, false, notValid, false, null, rawSql));
            }
            if (e.getColDataTypeList() != null && !e.getColDataTypeList().isEmpty()) {
                var cdt = e.getColDataTypeList().get(0);
                List<String> specs = cdt.getColumnSpecs() == null ? List.of() : cdt.getColumnSpecs();
                String joined = String.join(" ", specs).toUpperCase(Locale.ROOT);
                return Optional.of(new DdlFacts(DdlOperation.ADD_COLUMN, table,
                        strip(cdt.getColumnName()), null,
                        String.valueOf(cdt.getColDataType()), joined.contains("DEFAULT") ? joined : null,
                        defaultFunction(rawSql), joined.contains("NOT NULL"),
                        notValid, false, null, rawSql));
            }
        }
        if ("ALTER".equals(op) && e.getColDataTypeList() != null && !e.getColDataTypeList().isEmpty()) {
            var cdt = e.getColDataTypeList().get(0);
            String type = String.valueOf(cdt.getColDataType());
            // "SET NOT NULL" arrives with type=SET, specs=[NOT, NULL].
            if ("SET".equalsIgnoreCase(type)) {
                return Optional.of(new DdlFacts(DdlOperation.SET_NOT_NULL, table,
                        strip(cdt.getColumnName()), null, null, null, null, true,
                        notValid, false, null, rawSql));
            }
            // "DROP NOT NULL" arrives with colDataType=null, specs=[DROP, NOT, NULL] — it is
            // metadata-only, not a type change, but no rule models it correctly; fail closed.
            if (cdt.getColDataType() == null) return Optional.empty();
            return Optional.of(new DdlFacts(DdlOperation.ALTER_COLUMN_TYPE, table,
                    strip(cdt.getColumnName()), null, type, null, null, false,
                    notValid, false, null, rawSql));
        }
        return Optional.empty();
    }

    private String defaultFunction(String sql) {
        var m = DEFAULT_FN.matcher(sql);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private String referencedTable(String sql) {
        var m = REFERENCES.matcher(sql);
        return m.find() ? strip(m.group(1)) : null;
    }

    private String strip(String s) {
        return s == null ? null : s.replace("\"", "");
    }

    private static int trimEnd(String s) {
        int e = s.length();
        while (e > 0) {
            char c = s.charAt(e - 1);
            if (c == ';' || Character.isWhitespace(c)) e--;
            else break;
        }
        return e;
    }

    // A regex tail-match here (\s+NOT\s+VALID...$ via find()) is quadratic on attacker-
    // controlled input: CodeQL flagged it, and it measured at 35s for 80KB of input.
    // The clause can only ever be a bounded tail, so scan indices instead of the whole string.
    private static int notValidStart(String s) {
        int e = trimEnd(s);
        int p = e - 5;
        if (p < 0 || !s.regionMatches(true, p, "VALID", 0, 5)) return -1;
        int q = p;
        while (q > 0 && Character.isWhitespace(s.charAt(q - 1))) q--;
        if (q == p) return -1;
        int r = q - 3;
        if (r < 0 || !s.regionMatches(true, r, "NOT", 0, 3)) return -1;
        int t = r;
        while (t > 0 && Character.isWhitespace(s.charAt(t - 1))) t--;
        if (t == r) return -1;
        return t;
    }
}
