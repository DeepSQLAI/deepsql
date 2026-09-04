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

    private static final Pattern NOT_VALID = Pattern.compile("(?i)\\s+NOT\\s+VALID\\s*;?\\s*$");
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

        boolean notValid = NOT_VALID.matcher(sql).find();
        String normalized = notValid ? NOT_VALID.matcher(sql).replaceAll("") : sql;

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

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(normalized);
        } catch (Exception e) {
            return Optional.empty();
        }

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
        AlterExpression e = exprs.get(0);
        String table = strip(alter.getTable().getName());
        String op = e.getOperation() == null ? "" : e.getOperation().name().toUpperCase(Locale.ROOT);

        if ("DROP".equals(op)) {
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
            if (rawSql.toUpperCase(Locale.ROOT).contains("CHECK")) {
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
}
