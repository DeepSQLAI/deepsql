# Pre-flight Migration Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a deterministic Postgres DDL risk analyzer, exposed as one MCP tool `analyze_migration`, that tells a user whether a migration will lock or rewrite a table before they run it.

**Architecture:** Four stages — normalize/parse (JSqlParser 5.2 + a pre-parse shim), classify against a measured rule table, enrich with live catalog facts (`pg_proc.provolatile`, table size), and scale to a duration bucket. No LLM: the rules and the catalog decide the verdict, matching `IndexAdvisorService`. Reached through `DatabaseDialect` so there is no if/else on database type.

**Tech Stack:** Java 25, Spring Boot 4, JSqlParser 5.2, JUnit 5, Testcontainers (Postgres 18), Node (MCP layer).

**Spec:** `docs/superpowers/specs/2026-09-04-preflight-migration-review-design.md`

## Global Constraints

- **No JDK or Maven on this host.** All Java build/test runs go through Docker:
  `docker run --rm -v "$PWD/backend:/app" -v "$HOME/.m2:/root/.m2" -w /app eclipse-temurin:25-jdk-noble bash -c "apt-get update -qq && apt-get install -y -qq maven && mvn <goal>"`.
  Prefer adding a helper script over retyping this.
- **Fail closed.** Anything unparsed, unrecognised, or non-Postgres returns
  `verdict=UNKNOWN, safeToRun=false`. Never "looks fine".
- **No if/else on database type** — resolve via `DatabaseProviderRegistry.getDialect(dbType)`.
- **Authorization is not automatic.** Any endpoint taking a `connectionId` must call
  `accessControlService.assertCanReadConnectionContent(connectionId)` itself.
- **Read-only.** The analyzer never executes the DDL it analyses. Catalog queries only.
- Postgres only. MySQL returns `UNKNOWN` with `dialectSupported=false` — never a guess.
- Rule table is tagged `postgres>=11`, verified on 18.4.

## Verified facts this plan depends on

Measured during design, not recalled. Do not "correct" these from memory:

- `now()` is **STABLE** (`provolatile='s'`) → `ADD COLUMN ... DEFAULT now()` does **not** rewrite.
  `random()`, `gen_random_uuid()`, `clock_timestamp()` are **VOLATILE** (`'v'`) → they **do**.
- `ADD FOREIGN KEY` takes `ShareRowExclusiveLock` on **both** child and referenced table.
- `max(mode)` on `pg_locks` sorts **alphabetically** — "ShareLock" > "AccessExclusiveLock".
  Rank by explicit strength ordering, never string comparison.
- **JSqlParser 5.2 cannot parse** `... NOT VALID`, `CREATE INDEX CONCURRENTLY`.
  A pre-parse shim strips them into flags; verified working.
- JSqlParser quirks: `RENAME COLUMN a TO b` exposes only the **new** name via
  `getColumnName()`; `SET NOT NULL` arrives as `type=SET`, `specs=[NOT, NULL]`;
  `VALIDATE CONSTRAINT` degrades to `operation=UNSPECIFIC`.

## File Structure

| File | Responsibility |
|---|---|
| `provider/api/MigrationRiskProvider.java` | Interface: classify a parsed fact set → risk |
| `provider/api/DatabaseDialect.java` (modify) | Add `migrationRisk()` accessor |
| `provider/postgres/PostgresMigrationRiskProvider.java` | Postgres rule table + catalog enrichment |
| `provider/postgres/PostgresDialect.java` (modify) | Wire the new provider |
| `provider/mysql/MySQLMigrationRiskProvider.java` | Returns UNKNOWN, `dialectSupported=false` |
| `provider/mysql/MySQLDialect.java` (modify) | Wire the stub |
| `service/migration/DdlStatementParser.java` | Normalize shim + JSqlParser → `DdlFacts` |
| `service/migration/DdlFacts.java` | Record: operation, table, column, type, default, flags |
| `service/migration/MigrationRiskService.java` | Orchestrates parse → classify → enrich |
| `dto/MigrationRiskReport.java` | Response record (locks are **per table**) |
| `controller/MigrationRiskController.java` | `POST /migrations/analyze`, asserts read access |
| `test/.../DdlStatementParserTest.java` | Parser unit tests incl. the three shim cases |
| `test/.../PostgresMigrationRiskRulesTest.java` | Rule table unit tests |
| `test/.../PostgresMigrationRiskVerificationTest.java` | **Testcontainers: engine proves each rule** |
| `mcp/deepsql-phase1-lib.js` (modify) | Tool definition + handler + result + summarizer |
| `mcp/src/commands/migration.js` | CLI subcommand |
| `mcp/src/cli.js` (modify) | Help text |

---

### Task 1: DDL parser with the normalization shim

**Files:**
- Create: `backend/src/main/java/com/dbaagent/service/migration/DdlFacts.java`
- Create: `backend/src/main/java/com/dbaagent/service/migration/DdlStatementParser.java`
- Test: `backend/src/test/java/com/dbaagent/service/migration/DdlStatementParserTest.java`

**Interfaces:**
- Consumes: JSqlParser 5.2 (`CCJSqlParserUtil`, `Alter`, `AlterExpression`, `CreateIndex`).
- Produces: `DdlFacts` record and `DdlStatementParser.parse(String sql) -> Optional<DdlFacts>`
  (empty = unparseable = caller must fail closed).

`DdlFacts` fields:
`DdlOperation operation, String table, String column, String newColumnName, String dataType,
 String defaultExpression, String defaultFunction, boolean notNull, boolean notValid,
 boolean concurrently, String referencedTable, String rawSql`

`DdlOperation` enum:
`ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, ALTER_COLUMN_TYPE, SET_NOT_NULL,
 ADD_CHECK, ADD_FOREIGN_KEY, VALIDATE_CONSTRAINT, CREATE_INDEX, UNKNOWN`

- [ ] **Step 1: Write the failing test**

```java
package com.dbaagent.service.migration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DdlStatementParserTest {

    private final DdlStatementParser parser = new DdlStatementParser();

    @Test
    void addColumnWithVolatileDefault_extractsFunctionName() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN c uuid DEFAULT gen_random_uuid()").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_COLUMN);
        assertThat(f.table()).isEqualTo("orders");
        assertThat(f.column()).isEqualTo("c");
        assertThat(f.defaultFunction()).isEqualTo("gen_random_uuid");
    }

    @Test
    void addColumnWithoutDefault_hasNoDefaultFunction() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN c text").orElseThrow();
        assertThat(f.defaultFunction()).isNull();
        assertThat(f.notNull()).isFalse();
    }

    @Test
    void addColumnNotNull_setsFlag() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN c text NOT NULL").orElseThrow();
        assertThat(f.notNull()).isTrue();
    }

    // JSqlParser 5.2 cannot parse NOT VALID — the shim must strip it into a flag.
    @Test
    void addCheckNotValid_parsesViaShim() {
        var f = parser.parse("ALTER TABLE orders ADD CONSTRAINT ck CHECK (id > 0) NOT VALID").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_CHECK);
        assertThat(f.notValid()).isTrue();
    }

    @Test
    void addForeignKeyNotValid_capturesReferencedTable() {
        var f = parser.parse(
            "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (t_id) REFERENCES t(id) NOT VALID").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_FOREIGN_KEY);
        assertThat(f.notValid()).isTrue();
        assertThat(f.referencedTable()).isEqualTo("t");
    }

    // JSqlParser 5.2 cannot parse CONCURRENTLY — the shim must strip it into a flag.
    @Test
    void createIndexConcurrently_parsesViaShim() {
        var f = parser.parse("CREATE INDEX CONCURRENTLY idx ON orders (a)").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.CREATE_INDEX);
        assertThat(f.concurrently()).isTrue();
        assertThat(f.table()).isEqualTo("orders");
    }

    @Test
    void createIndexPlain_isNotConcurrent() {
        var f = parser.parse("CREATE INDEX idx ON orders (a)").orElseThrow();
        assertThat(f.concurrently()).isFalse();
    }

    @Test
    void setNotNull_isDistinguishedFromAlterType() {
        var f = parser.parse("ALTER TABLE orders ALTER COLUMN a SET NOT NULL").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.SET_NOT_NULL);
    }

    @Test
    void alterColumnType_capturesTargetType() {
        var f = parser.parse("ALTER TABLE orders ALTER COLUMN a TYPE varchar(50)").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ALTER_COLUMN_TYPE);
        assertThat(f.dataType()).containsIgnoringCase("varchar");
    }

    @Test
    void garbageInput_returnsEmptySoCallerFailsClosed() {
        assertThat(parser.parse("this is not sql at all")).isEmpty();
    }

    @Test
    void selectStatement_returnsEmpty() {
        assertThat(parser.parse("SELECT * FROM orders")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./scripts/jtest.sh -Dtest=DdlStatementParserTest`
Expected: compilation failure — `DdlStatementParser` does not exist.

(Create `scripts/jtest.sh` first — see Task 0 note below.)

- [ ] **Step 3: Implement `DdlFacts` and `DdlOperation`**

```java
package com.dbaagent.service.migration;

public enum DdlOperation {
    ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, ALTER_COLUMN_TYPE, SET_NOT_NULL,
    ADD_CHECK, ADD_FOREIGN_KEY, VALIDATE_CONSTRAINT, CREATE_INDEX, UNKNOWN
}
```

```java
package com.dbaagent.service.migration;

public record DdlFacts(
        DdlOperation operation,
        String table,
        String column,
        String newColumnName,
        String dataType,
        String defaultExpression,
        String defaultFunction,
        boolean notNull,
        boolean notValid,
        boolean concurrently,
        String referencedTable,
        String rawSql) {}
```

- [ ] **Step 4: Implement the parser**

The shim is load-bearing: JSqlParser 5.2 throws on `NOT VALID` and `CONCURRENTLY`,
both of which are the *safe* forms this tool recommends.

```java
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
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./scripts/jtest.sh -Dtest=DdlStatementParserTest`
Expected: PASS, 11 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/dbaagent/service/migration backend/src/test/java/com/dbaagent/service/migration scripts/jtest.sh
git commit -m "feat: DDL statement parser with JSqlParser 5.2 normalization shim"
```

**Task 0 note (fold into Step 2):** create `scripts/jtest.sh`:

```bash
#!/usr/bin/env bash
# No JDK on the host — run Maven goals in the same JDK image the backend builds with.
set -euo pipefail
exec docker run --rm \
  -v "$(git rev-parse --show-toplevel)/backend:/app" \
  -v "$HOME/.m2:/root/.m2" \
  -w /app maven:3-eclipse-temurin-25 \
  mvn -q -o test "$@"
```

---

### Task 2: The rule table

**Files:**
- Create: `backend/src/main/java/com/dbaagent/provider/api/MigrationRiskProvider.java`
- Create: `backend/src/main/java/com/dbaagent/dto/MigrationRiskReport.java`
- Create: `backend/src/main/java/com/dbaagent/provider/postgres/PostgresMigrationRiskProvider.java`
- Test: `backend/src/test/java/com/dbaagent/provider/postgres/PostgresMigrationRiskRulesTest.java`

**Interfaces:**
- Consumes: `DdlFacts`, `DdlOperation` from Task 1.
- Produces: `MigrationRiskProvider.classify(DdlFacts facts, TableFacts table) -> MigrationRiskReport`,
  `MigrationRiskReport` record, `TableFacts(long rowEstimate, long sizeBytes, boolean empty)`,
  `LockRef(String table, String mode, List<String> blocks)`.

- [ ] **Step 1: Write the failing test**

```java
package com.dbaagent.provider.postgres;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.service.migration.DdlFacts;
import com.dbaagent.service.migration.DdlOperation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresMigrationRiskRulesTest {

    private final PostgresMigrationRiskProvider provider = new PostgresMigrationRiskProvider();
    private final TableFacts big = new TableFacts(48_000_000L, 9_200_000_000L, false);
    private final TableFacts small = new TableFacts(500L, 40_000L, false);

    private DdlFacts addColumn(String defaultFn, boolean notNull) {
        return new DdlFacts(DdlOperation.ADD_COLUMN, "orders", "c", null, "text",
                defaultFn == null ? null : "DEFAULT", defaultFn, notNull, false, false, null, "sql");
    }

    // MEASURED: now() is STABLE, so this does NOT rewrite.
    @Test
    void addColumnWithStableDefault_doesNotRewrite() {
        var r = provider.classify(addColumn("now", false), big);
        assertThat(r.rewritesTable()).isFalse();
        assertThat(r.verdict()).isEqualTo("SAFE");
    }

    // MEASURED: gen_random_uuid() is VOLATILE, so this DOES rewrite.
    @Test
    void addColumnWithVolatileDefaultOnBigTable_isDanger() {
        var r = provider.classify(addColumn("gen_random_uuid", false), big);
        assertThat(r.rewritesTable()).isTrue();
        assertThat(r.verdict()).isEqualTo("DANGER");
        assertThat(r.saferAlternative()).isNotBlank();
    }

    @Test
    void addColumnWithVolatileDefaultOnSmallTable_isCaution() {
        var r = provider.classify(addColumn("random", false), small);
        assertThat(r.rewritesTable()).isTrue();
        assertThat(r.verdict()).isEqualTo("CAUTION");
    }

    @Test
    void addColumnNotNullNoDefaultOnNonEmptyTable_isFailsOutright() {
        var r = provider.classify(addColumn(null, true), big);
        assertThat(r.verdict()).isEqualTo("FAILS");
        assertThat(r.reason()).containsIgnoringCase("null values");
    }

    @Test
    void addColumnPlain_isSafeEvenOnHugeTable() {
        var r = provider.classify(addColumn(null, false), big);
        assertThat(r.verdict()).isEqualTo("SAFE");
        assertThat(r.locks()).anySatisfy(l -> assertThat(l.mode()).isEqualTo("AccessExclusiveLock"));
    }

    // MEASURED: FK locks BOTH tables.
    @Test
    void addForeignKey_reportsLockOnReferencedTableToo() {
        var f = new DdlFacts(DdlOperation.ADD_FOREIGN_KEY, "child", null, null, null,
                null, null, false, false, false, "orders", "sql");
        var r = provider.classify(f, big);
        assertThat(r.locks()).extracting(MigrationRiskReport.LockRef::table)
                .containsExactlyInAnyOrder("child", "orders");
    }

    @Test
    void addForeignKeyNotValid_isSaferThanValidating() {
        var validating = new DdlFacts(DdlOperation.ADD_FOREIGN_KEY, "child", null, null, null,
                null, null, false, false, false, "orders", "sql");
        var notValid = new DdlFacts(DdlOperation.ADD_FOREIGN_KEY, "child", null, null, null,
                null, null, false, true, false, "orders", "sql");
        assertThat(provider.classify(validating, big).verdict()).isEqualTo("DANGER");
        assertThat(provider.classify(notValid, big).verdict()).isEqualTo("CAUTION");
    }

    @Test
    void createIndexConcurrently_isSafeAndPlainIsNot() {
        var plain = new DdlFacts(DdlOperation.CREATE_INDEX, "orders", null, null, null,
                null, null, false, false, false, null, "sql");
        var conc = new DdlFacts(DdlOperation.CREATE_INDEX, "orders", null, null, null,
                null, null, false, false, true, null, "sql");
        assertThat(provider.classify(plain, big).verdict()).isEqualTo("DANGER");
        assertThat(provider.classify(conc, big).verdict()).isEqualTo("SAFE");
    }

    @Test
    void dropColumn_isMetadataOnly() {
        var f = new DdlFacts(DdlOperation.DROP_COLUMN, "orders", "a", null, null,
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.rewritesTable()).isFalse();
        assertThat(r.verdict()).isEqualTo("SAFE");
    }

    @Test
    void unknownOperation_failsClosed() {
        var f = new DdlFacts(DdlOperation.UNKNOWN, "orders", null, null, null,
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.verdict()).isEqualTo("UNKNOWN");
        assertThat(r.safeToRun()).isFalse();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./scripts/jtest.sh -Dtest=PostgresMigrationRiskRulesTest`
Expected: compilation failure — classes do not exist.

- [ ] **Step 3: Implement the DTOs**

```java
package com.dbaagent.dto;

public record TableFacts(long rowEstimate, long sizeBytes, boolean empty) {}
```

```java
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
```

- [ ] **Step 4: Implement the provider interface and Postgres rules**

```java
package com.dbaagent.provider.api;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.service.migration.DdlFacts;

public interface MigrationRiskProvider {
    MigrationRiskReport classify(DdlFacts facts, TableFacts table);
}
```

`PostgresMigrationRiskProvider` implements the measured table. Key points:
- `LARGE_ROWS = 1_000_000L` separates CAUTION from DANGER for rewriting operations.
- Volatility is decided by the caller (Task 3) passing `defaultFunction`; the provider
  keeps a fallback set `{random, gen_random_uuid, clock_timestamp, uuid_generate_v4}`
  for when the catalog is unavailable, and treats `now`/`current_timestamp` as stable.
- Every branch sets `docsUrl` to
  `https://www.postgresql.org/docs/18/sql-altertable.html` (or `sql-createindex.html`).
- Duration buckets: `"milliseconds"`, `"seconds"`, `"minutes"`,
  `"minutes-to-tens-of-minutes"`, `"unknown"`.

```java
package com.dbaagent.provider.postgres;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.MigrationRiskReport.LockRef;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.provider.api.MigrationRiskProvider;
import com.dbaagent.service.migration.DdlFacts;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class PostgresMigrationRiskProvider implements MigrationRiskProvider {

    private static final long LARGE_ROWS = 1_000_000L;
    private static final String ALTER_DOCS = "https://www.postgresql.org/docs/18/sql-altertable.html";
    private static final String INDEX_DOCS = "https://www.postgresql.org/docs/18/sql-createindex.html";
    private static final Set<String> VOLATILE_FALLBACK =
            Set.of("random", "gen_random_uuid", "clock_timestamp", "uuid_generate_v4");

    private static final List<String> RW = List.of("read", "write");
    private static final List<String> W = List.of("write");

    @Override
    public MigrationRiskReport classify(DdlFacts f, TableFacts t) {
        return switch (f.operation()) {
            case ADD_COLUMN -> addColumn(f, t);
            case DROP_COLUMN -> metadataOnly(f, t, "Dropping a column is metadata-only; the data is reclaimed later by VACUUM.");
            case RENAME_COLUMN -> metadataOnly(f, t, "Renaming is metadata-only, but it will break application code referencing the old name.");
            case ALTER_COLUMN_TYPE -> alterType(f, t);
            case SET_NOT_NULL -> setNotNull(f, t);
            case ADD_CHECK -> addCheck(f, t);
            case ADD_FOREIGN_KEY -> addForeignKey(f, t);
            case VALIDATE_CONSTRAINT -> validateConstraint(f, t);
            case CREATE_INDEX -> createIndex(f, t);
            case UNKNOWN -> unknown(f, t);
        };
    }

    private boolean isVolatile(String fn) {
        return fn != null && VOLATILE_FALLBACK.contains(fn.toLowerCase());
    }

    private String bucket(TableFacts t) {
        if (t.rowEstimate() >= 10_000_000L) return "minutes-to-tens-of-minutes";
        if (t.rowEstimate() >= LARGE_ROWS) return "minutes";
        return "seconds";
    }

    private MigrationRiskReport addColumn(DdlFacts f, TableFacts t) {
        var locks = List.of(new LockRef(f.table(), "AccessExclusiveLock", RW));
        if (f.notNull() && f.defaultFunction() == null && f.defaultExpression() == null && !t.empty()) {
            return report("FAILS", false, f, t, locks, false, "milliseconds",
                    "ADD COLUMN ... NOT NULL without a default fails on a non-empty table: "
                    + "the existing rows contain null values.",
                    "Add the column nullable, backfill in batches, then SET NOT NULL.", ALTER_DOCS);
        }
        if (isVolatile(f.defaultFunction())) {
            boolean big = t.rowEstimate() >= LARGE_ROWS;
            return report(big ? "DANGER" : "CAUTION", false, f, t, locks, true, bucket(t),
                    "Default expression " + f.defaultFunction()
                    + "() is VOLATILE, so every existing row must be written — a full table rewrite "
                    + "under ACCESS EXCLUSIVE, blocking reads and writes for the whole operation.",
                    "Add the column without a default, backfill in batches, then set the default "
                    + "for future rows.", ALTER_DOCS);
        }
        return report("SAFE", true, f, t, locks, false, "milliseconds",
                "Adding a column with no default, a constant default, or a STABLE default "
                + "(such as now()) is metadata-only in PostgreSQL 11+. The ACCESS EXCLUSIVE lock "
                + "is held only briefly.", null, ALTER_DOCS);
    }

    private MigrationRiskReport metadataOnly(DdlFacts f, TableFacts t, String why) {
        return report("SAFE", true, f, t,
                List.of(new LockRef(f.table(), "AccessExclusiveLock", RW)),
                false, "milliseconds", why, null, ALTER_DOCS);
    }

    private MigrationRiskReport alterType(DdlFacts f, TableFacts t) {
        boolean big = t.rowEstimate() >= LARGE_ROWS;
        return report(big ? "DANGER" : "CAUTION", false, f, t,
                List.of(new LockRef(f.table(), "AccessExclusiveLock", RW)),
                true, bucket(t),
                "Changing a column type generally rewrites the whole table under ACCESS EXCLUSIVE. "
                + "(Widening within the same type family — varchar(50) to varchar(100) — is the "
                + "exception and does not rewrite.)",
                "Add a new column, backfill in batches, swap the names, then drop the old column.",
                ALTER_DOCS);
    }

    private MigrationRiskReport setNotNull(DdlFacts f, TableFacts t) {
        return report(t.rowEstimate() >= LARGE_ROWS ? "CAUTION" : "SAFE",
                t.rowEstimate() < LARGE_ROWS, f, t,
                List.of(new LockRef(f.table(), "AccessExclusiveLock", RW)),
                false, bucket(t),
                "SET NOT NULL does not rewrite the table, but it scans every row to validate, "
                + "holding ACCESS EXCLUSIVE for the duration of the scan.",
                "Add a NOT VALID CHECK (col IS NOT NULL) constraint, VALIDATE it under a weaker "
                + "lock, then SET NOT NULL.", ALTER_DOCS);
    }

    private MigrationRiskReport addCheck(DdlFacts f, TableFacts t) {
        var locks = List.of(new LockRef(f.table(), "AccessExclusiveLock", RW));
        if (f.notValid()) {
            return report("SAFE", true, f, t, locks, false, "milliseconds",
                    "ADD CONSTRAINT ... NOT VALID skips the validating scan, so the ACCESS "
                    + "EXCLUSIVE lock is held only briefly.",
                    "Follow with VALIDATE CONSTRAINT, which runs under a weaker lock that allows "
                    + "concurrent writes.", ALTER_DOCS);
        }
        return report(t.rowEstimate() >= LARGE_ROWS ? "DANGER" : "CAUTION", false, f, t, locks,
                false, bucket(t),
                "A validating CHECK constraint scans every row while holding ACCESS EXCLUSIVE, "
                + "blocking reads and writes.",
                "Add it NOT VALID first, then VALIDATE CONSTRAINT separately.", ALTER_DOCS);
    }

    private MigrationRiskReport addForeignKey(DdlFacts f, TableFacts t) {
        var locks = f.referencedTable() == null
                ? List.of(new LockRef(f.table(), "ShareRowExclusiveLock", W))
                : List.of(new LockRef(f.table(), "ShareRowExclusiveLock", W),
                          new LockRef(f.referencedTable(), "ShareRowExclusiveLock", W));
        String bothTables = "This locks the referenced table as well as the one being altered — "
                + "writes are blocked on a table the statement does not name.";
        if (f.notValid()) {
            return report("CAUTION", false, f, t, locks, false, "seconds",
                    "NOT VALID skips the validating scan, but ShareRowExclusiveLock is still taken "
                    + "on both tables. " + bothTables,
                    "Follow with VALIDATE CONSTRAINT under ShareUpdateExclusiveLock.", ALTER_DOCS);
        }
        return report("DANGER", false, f, t, locks, false, bucket(t),
                "Adding a validating foreign key scans the child table and blocks writes on both "
                + "tables under ShareRowExclusiveLock. " + bothTables,
                "Add the constraint NOT VALID, then VALIDATE CONSTRAINT separately.", ALTER_DOCS);
    }

    private MigrationRiskReport validateConstraint(DdlFacts f, TableFacts t) {
        return report("SAFE", true, f, t,
                List.of(new LockRef(f.table(), "ShareUpdateExclusiveLock", List.of())),
                false, bucket(t),
                "VALIDATE CONSTRAINT scans the table under ShareUpdateExclusiveLock, which allows "
                + "concurrent reads and writes. This is the cheap second half of the NOT VALID pattern.",
                null, ALTER_DOCS);
    }

    private MigrationRiskReport createIndex(DdlFacts f, TableFacts t) {
        if (f.concurrently()) {
            return report("SAFE", true, f, t,
                    List.of(new LockRef(f.table(), "ShareUpdateExclusiveLock", List.of())),
                    false, bucket(t),
                    "CREATE INDEX CONCURRENTLY does not block reads or writes. It takes longer and "
                    + "cannot run inside a transaction block.",
                    "If it fails it leaves an INVALID index behind — check with \\d and drop it "
                    + "before retrying.", INDEX_DOCS);
        }
        return report(t.rowEstimate() >= LARGE_ROWS ? "DANGER" : "CAUTION", false, f, t,
                List.of(new LockRef(f.table(), "ShareLock", W)),
                false, bucket(t),
                "A plain CREATE INDEX blocks all writes to the table for the entire build.",
                "Use CREATE INDEX CONCURRENTLY, which allows concurrent writes.", INDEX_DOCS);
    }

    private MigrationRiskReport unknown(DdlFacts f, TableFacts t) {
        return report("UNKNOWN", false, f, t, List.of(), false, "unknown",
                "This statement is not one DeepSQL has a verified rule for. Treat it as unsafe "
                + "until reviewed by hand.", null, ALTER_DOCS);
    }

    private MigrationRiskReport report(String verdict, boolean safe, DdlFacts f, TableFacts t,
                                       List<LockRef> locks, boolean rewrites, String duration,
                                       String reason, String safer, String docs) {
        return new MigrationRiskReport("postgres", verdict, safe, true, f.operation().name(),
                f.table(), locks, rewrites, t.rowEstimate(), t.sizeBytes(), duration,
                reason, safer, docs, "verified");
    }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./scripts/jtest.sh -Dtest=PostgresMigrationRiskRulesTest`
Expected: PASS, 11 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/dbaagent/provider backend/src/main/java/com/dbaagent/dto backend/src/test/java/com/dbaagent/provider
git commit -m "feat: Postgres DDL migration risk rule table"
```

---

### Task 3: Engine verification with Testcontainers

This is the task that makes the tool trustworthy — every rule is proved against a real
PostgreSQL rather than against the rule table that produced it.

**Files:**
- Modify: `backend/pom.xml` (add `testcontainers` + `postgresql` test scope)
- Test: `backend/src/test/java/com/dbaagent/provider/postgres/PostgresMigrationRiskVerificationTest.java`

**Interfaces:**
- Consumes: `PostgresMigrationRiskProvider.classify`, `DdlStatementParser.parse`.
- Produces: nothing — it is a verification harness.

- [ ] **Step 1: Add the Testcontainers dependencies**

```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <version>1.20.4</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>1.20.4</version>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the verification test**

Each case runs the DDL in a transaction, reads what PostgreSQL actually did, and rolls
back. `pg_relation_filenode()` changing proves a physical rewrite; `pg_locks` reports the
real lock. Lock strength is ranked explicitly — `max(mode)` is alphabetical and would
report "ShareLock" as stronger than "AccessExclusiveLock".

```java
package com.dbaagent.provider.postgres;

import com.dbaagent.dto.TableFacts;
import com.dbaagent.service.migration.DdlStatementParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresMigrationRiskVerificationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18");

    private static final List<String> LOCK_STRENGTH = List.of(
            "AccessShareLock", "RowShareLock", "RowExclusiveLock", "ShareUpdateExclusiveLock",
            "ShareLock", "ShareRowExclusiveLock", "ExclusiveLock", "AccessExclusiveLock");

    private final DdlStatementParser parser = new DdlStatementParser();
    private final PostgresMigrationRiskProvider provider = new PostgresMigrationRiskProvider();

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS child, t CASCADE");
            s.execute("CREATE TABLE t (id bigserial primary key, a text)");
            s.execute("INSERT INTO t (a) SELECT 'x' FROM generate_series(1,1000)");
            s.execute("CREATE TABLE child (id bigserial primary key, t_id bigint)");
            s.execute("INSERT INTO child (t_id) SELECT id FROM t LIMIT 500");
        }
    }

    @AfterEach
    void tearDown() throws SQLException { conn.close(); }

    @ParameterizedTest(name = "{0} -> rewrites={1}")
    @CsvSource({
        "ALTER TABLE t ADD COLUMN c text,                                  false",
        "ALTER TABLE t ADD COLUMN c text DEFAULT 'x',                      false",
        "ALTER TABLE t ADD COLUMN c timestamptz DEFAULT now(),             false",
        "ALTER TABLE t ADD COLUMN c uuid DEFAULT gen_random_uuid(),        true",
        "ALTER TABLE t ADD COLUMN c double precision DEFAULT random(),     true",
        "ALTER TABLE t ADD COLUMN c timestamptz DEFAULT clock_timestamp(), true",
        "ALTER TABLE t ALTER COLUMN a TYPE varchar(50),                    true",
        "ALTER TABLE t DROP COLUMN a,                                      false",
        "ALTER TABLE t ALTER COLUMN a SET NOT NULL,                        false"
    })
    void ruleTableMatchesEngineRewriteBehaviour(String sql, boolean expectedRewrite) throws SQLException {
        conn.setAutoCommit(false);
        long before = filenode("t");
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
        long after = filenode("t");
        conn.rollback();
        conn.setAutoCommit(true);

        boolean actuallyRewrote = before != after;
        assertThat(actuallyRewrote)
            .as("engine rewrite behaviour for: %s", sql)
            .isEqualTo(expectedRewrite);

        var facts = parser.parse(sql).orElseThrow();
        var report = provider.classify(facts, new TableFacts(1000L, 40_000L, false));
        assertThat(report.rewritesTable())
            .as("rule table claim vs engine for: %s", sql)
            .isEqualTo(actuallyRewrote);
    }

    @Test
    void addForeignKeyLocksBothTables() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (t_id) REFERENCES t(id)");
        }
        assertThat(strongestLock("t")).isEqualTo("ShareRowExclusiveLock");
        assertThat(strongestLock("child")).isEqualTo("ShareRowExclusiveLock");
        conn.rollback();
        conn.setAutoCommit(true);

        var facts = parser.parse(
            "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (t_id) REFERENCES t(id)").orElseThrow();
        var report = provider.classify(facts, new TableFacts(1000L, 40_000L, false));
        assertThat(report.locks()).extracting(l -> l.table())
                .containsExactlyInAnyOrder("child", "t");
    }

    @Test
    void addColumnNotNullWithoutDefaultReallyFails() {
        Assertions.assertThatThrownBy(() -> {
            try (Statement s = conn.createStatement()) {
                s.execute("ALTER TABLE t ADD COLUMN c5 text NOT NULL");
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("null values");
    }

    @Test
    void volatilityClassificationMatchesCatalog() throws SQLException {
        assertThat(volatility("now")).isEqualTo("s");
        assertThat(volatility("random")).isEqualTo("v");
        assertThat(volatility("gen_random_uuid")).isEqualTo("v");
        assertThat(volatility("clock_timestamp")).isEqualTo("v");
    }

    private long filenode(String table) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT pg_relation_filenode(?::regclass)")) {
            p.setString(1, table);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private String strongestLock(String table) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT mode FROM pg_locks WHERE relation = ?::regclass")) {
            p.setString(1, table);
            try (ResultSet rs = p.executeQuery()) {
                String strongest = null;
                while (rs.next()) {
                    String m = rs.getString(1);
                    if (strongest == null || LOCK_STRENGTH.indexOf(m) > LOCK_STRENGTH.indexOf(strongest)) {
                        strongest = m;
                    }
                }
                return strongest;
            }
        }
    }

    private String volatility(String fn) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT provolatile FROM pg_proc WHERE proname = ? LIMIT 1")) {
            p.setString(1, fn);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }
}
```

- [ ] **Step 3: Run it and confirm it passes**

Run: `./scripts/jtest.sh -Dtest=PostgresMigrationRiskVerificationTest`
Expected: PASS. A failure here means the rule table disagrees with the engine — fix the
rule, never the assertion.

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml backend/src/test/java/com/dbaagent/provider/postgres/PostgresMigrationRiskVerificationTest.java
git commit -m "test: verify DDL risk rules against a real PostgreSQL"
```

---

### Task 4: Service, dialect wiring, MySQL stub, endpoint

**Files:**
- Create: `backend/src/main/java/com/dbaagent/service/migration/MigrationRiskService.java`
- Create: `backend/src/main/java/com/dbaagent/provider/mysql/MySQLMigrationRiskProvider.java`
- Create: `backend/src/main/java/com/dbaagent/controller/MigrationRiskController.java`
- Modify: `backend/src/main/java/com/dbaagent/provider/api/DatabaseDialect.java` (add `migrationRisk()`)
- Modify: `backend/src/main/java/com/dbaagent/provider/postgres/PostgresDialect.java`
- Modify: `backend/src/main/java/com/dbaagent/provider/mysql/MySQLDialect.java`
- Test: `backend/src/test/java/com/dbaagent/service/migration/MigrationRiskServiceTest.java`

**Interfaces:**
- Consumes: `DdlStatementParser.parse`, `MigrationRiskProvider.classify`,
  `CredentialService.getDecryptedConnection(connectionId)`,
  `ConnectionService.getJdbcTemplate(connectionId, request)`,
  `DatabaseProviderRegistry.getDialect(dbType)`,
  `AccessControlService.assertCanReadConnectionContent(connectionId)`.
- Produces: `MigrationRiskService.analyze(String connectionId, String sql) -> MigrationRiskReport`.

- [ ] **Step 1: Write the failing test**

```java
package com.dbaagent.service.migration;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.postgres.PostgresMigrationRiskProvider;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.security.AccessControlService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MigrationRiskServiceTest {

    @Test
    void unparseableSqlFailsClosedWithoutTouchingTheDatabase() {
        var access = mock(AccessControlService.class);
        var credentials = mock(CredentialService.class);
        var connections = mock(ConnectionService.class);
        var registry = mock(DatabaseProviderRegistry.class);

        var service = new MigrationRiskService(new DdlStatementParser(), registry,
                credentials, connections, access);

        MigrationRiskReport r = service.analyze("conn-1", "this is not sql");

        assertThat(r.verdict()).isEqualTo("UNKNOWN");
        assertThat(r.safeToRun()).isFalse();
        verify(access).assertCanReadConnectionContent("conn-1");
        verifyNoInteractions(connections);   // never opens a session for garbage input
    }

    @Test
    void authorizationIsAssertedBeforeAnyWork() {
        var access = mock(AccessControlService.class);
        doThrow(new RuntimeException("denied")).when(access).assertCanReadConnectionContent(anyString());
        var service = new MigrationRiskService(new DdlStatementParser(),
                mock(DatabaseProviderRegistry.class), mock(CredentialService.class),
                mock(ConnectionService.class), access);

        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> service.analyze("conn-1", "ALTER TABLE t ADD COLUMN c text"))
            .hasMessageContaining("denied");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./scripts/jtest.sh -Dtest=MigrationRiskServiceTest`
Expected: compilation failure — `MigrationRiskService` does not exist.

- [ ] **Step 3: Implement the service**

Order matters: authorize, then parse, then (only if parsed) open a session for catalog facts.

```java
package com.dbaagent.service.migration;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.TableFacts;
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

    private TableFacts tableFacts(String connectionId, Object request, String table) {
        try {
            var jdbc = connectionService.getJdbcTemplate(connectionId,
                    (com.dbaagent.model.ConnectionRequest) request);
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
```

- [ ] **Step 4: Wire the dialect and add the MySQL stub**

Add to `DatabaseDialect`:

```java
    /**
     * Get the migration risk provider for this database.
     * @return The migration risk provider
     */
    MigrationRiskProvider migrationRisk();
```

`PostgresDialect`: add the field `private final PostgresMigrationRiskProvider migrationRiskProvider;`
and the accessor returning it. `MySQLDialect`: same shape with the stub below.

```java
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
```

- [ ] **Step 5: Add the controller**

```java
package com.dbaagent.controller;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.service.migration.MigrationRiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/migrations")
@RequiredArgsConstructor
public class MigrationRiskController {

    private final MigrationRiskService migrationRiskService;

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        String connectionId = body.get("connectionId");
        String sql = body.get("sql");
        if (connectionId == null || connectionId.isBlank() || sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "connectionId and sql are required"));
        }
        try {
            MigrationRiskReport report = migrationRiskService.analyze(connectionId, sql);
            return ResponseEntity.ok(report);
        } catch (ResponseStatusException e) {
            throw e;   // preserve 403/404 — a catch-all below would report it as a 500
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Migration analysis failed: " + e.getMessage()));
        }
    }
}
```

- [ ] **Step 6: Run the full backend suite**

Run: `./scripts/jtest.sh -Dtest='*MigrationRisk*,*DdlStatement*'`
Expected: PASS. Then confirm `ConnectionScopedAuthorizationSafetyTest` still passes —
it scans every controller and will fail if the new endpoint is unguarded.

Run: `./scripts/jtest.sh -Dtest=ConnectionScopedAuthorizationSafetyTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/dbaagent
git commit -m "feat: migration risk service, dialect wiring, MySQL stub, analyze endpoint"
```

---

### Task 5: MCP tool + CLI + docs

Per CLAUDE.md's MCP & CLI Release Rules, **all** of these change in one commit.

**Files:**
- Modify: `mcp/deepsql-phase1-lib.js` (TOOL_DEFINITIONS, handleToolCall, buildToolResult, summarizeMigrationRisk)
- Create: `mcp/src/commands/migration.js`
- Modify: `mcp/src/cli.js` (COMMAND_HELP)
- Modify: `mcp/skills/SKILL_BODY.md` (tool table + count)
- Modify: `mcp/CLAUDE.md`, `mcp/README.md`
- Modify: `mcp/package.json` (minor bump)

**Interfaces:**
- Consumes: `POST /migrations/analyze` returning `MigrationRiskReport` from Task 4.
- Produces: MCP tool `analyze_migration(connectionId, sql)`.

- [ ] **Step 1: Add the tool definition**

```js
  {
    name: "analyze_migration",
    description:
      "Check whether a DDL statement is safe to run before running it. Returns a "
      + "deterministic verdict (SAFE/CAUTION/DANGER/FAILS/UNKNOWN) with the exact locks "
      + "taken — per table, since a foreign key locks the referenced table too — whether "
      + "the table is rewritten, a coarse duration estimate scaled by live table size, and "
      + "a safer alternative where one exists. Rules are verified against a real PostgreSQL, "
      + "not inferred: do not second-guess the verdict from memory. PostgreSQL only; MySQL "
      + "returns UNKNOWN rather than a guess. Read-only — it never executes the statement.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        sql: { type: "string", description: "The DDL statement to analyse, e.g. 'ALTER TABLE orders ADD COLUMN region text'." },
      },
      required: ["connectionId", "sql"],
    },
  },
```

- [ ] **Step 2: Add the handler case**

```js
    case "analyze_migration": {
      const { connectionId, sql } = args;
      const data = await apiPost("/migrations/analyze", { connectionId, sql });
      return buildToolResult("analyze_migration", data);
    }
```

- [ ] **Step 3: Add the summarizer**

```js
function summarizeMigrationRisk(r) {
  if (!r) return "No analysis returned.";
  if (r.verdict === "UNKNOWN") {
    return `UNKNOWN — ${r.reason} Treat as unsafe until reviewed by hand.`;
  }
  const locks = (r.locks || [])
    .map((l) => `${l.table}: ${l.mode}${l.blocks?.length ? ` (blocks ${l.blocks.join(" + ")})` : ""}`)
    .join("; ");
  const parts = [
    `${r.verdict} — ${r.operation} on ${r.table}.`,
    r.rewritesTable ? "Rewrites the whole table." : "No table rewrite.",
    locks ? `Locks — ${locks}.` : "",
    r.tableRows ? `Table has ~${Number(r.tableRows).toLocaleString()} rows.` : "",
    `Estimated duration: ${r.estimatedDuration}.`,
    r.reason,
    r.saferAlternative ? `Safer: ${r.saferAlternative}` : "",
  ];
  return parts.filter(Boolean).join(" ");
}
```

- [ ] **Step 4: Add the CLI subcommand**

```js
// mcp/src/commands/migration.js
import { apiPost } from "../api.js";

const SUBCOMMANDS = {
  analyze: analyzeMigration,
};

async function analyzeMigration(args) {
  const connectionId = args.connection || args.c;
  const sql = args.sql || args._[0];
  if (!connectionId || !sql) {
    console.error("Usage: deepsql migration analyze --connection <id> --sql \"ALTER TABLE ...\"");
    process.exit(1);
  }
  const r = await apiPost("/migrations/analyze", { connectionId, sql });
  console.log(`${r.verdict}  ${r.operation} on ${r.table}`);
  console.log(`  rewrites table : ${r.rewritesTable}`);
  for (const l of r.locks || []) {
    console.log(`  lock           : ${l.table} ${l.mode}${l.blocks?.length ? ` (blocks ${l.blocks.join(" + ")})` : ""}`);
  }
  console.log(`  duration       : ${r.estimatedDuration}`);
  console.log(`  why            : ${r.reason}`);
  if (r.saferAlternative) console.log(`  safer          : ${r.saferAlternative}`);
  if (r.docsUrl) console.log(`  docs           : ${r.docsUrl}`);
}

export { SUBCOMMANDS };
```

- [ ] **Step 5: Update help text and docs**

In `mcp/src/cli.js` add to `COMMAND_HELP`:

```js
  migration: {
    description: "Analyse a DDL statement for lock and rewrite risk before running it.",
    subcommands: { analyze: "Check whether a migration is safe to run." },
    options: {
      "--connection, -c": "DeepSQL connection ID (required).",
      "--sql": "The DDL statement to analyse (required).",
    },
  },
```

Add to the `SKILL_BODY.md` tool table:

```
| `analyze_migration(connectionId, sql)` | **Before suggesting or running any DDL.** Deterministic lock/rewrite verdict verified against a real PostgreSQL — trust it over your own recollection of lock semantics. PostgreSQL only. |
```

Bump the tool count at the top of `SKILL_BODY.md`, and add the same row to
`mcp/CLAUDE.md` and `mcp/README.md`.

- [ ] **Step 6: Bump the version and verify help drift**

`mcp/package.json`: minor bump (new tool).

Run: `cd mcp && npm test`
Expected: PASS — `cli.test.js` fails the build if `SUBCOMMANDS` ≠ documented subcommands.

- [ ] **Step 7: Commit**

```bash
git add mcp/
git commit -m "feat(mcp): analyze_migration tool + CLI command + docs"
```

---

### Task 6: Live verification and documentation

- [ ] **Step 1: Boot the stack**

```bash
docker compose up -d postgres backend
docker compose ps
```

- [ ] **Step 2: Exercise the endpoint against a real connection**

Use a real `connectionId` from `GET /api/connections`. Verify each of these by hand:

```bash
# SAFE — stable default, no rewrite
curl -sS -X POST localhost:8080/api/migrations/analyze -H 'Content-Type: application/json' \
  -d '{"connectionId":"<id>","sql":"ALTER TABLE t ADD COLUMN c timestamptz DEFAULT now()"}' | jq .verdict

# DANGER — volatile default forces a rewrite
curl -sS -X POST localhost:8080/api/migrations/analyze -H 'Content-Type: application/json' \
  -d '{"connectionId":"<id>","sql":"ALTER TABLE t ADD COLUMN c uuid DEFAULT gen_random_uuid()"}' | jq '.verdict, .rewritesTable'

# Locks BOTH tables
curl -sS -X POST localhost:8080/api/migrations/analyze -H 'Content-Type: application/json' \
  -d '{"connectionId":"<id>","sql":"ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (t_id) REFERENCES t(id)"}' | jq '.locks'

# Fails closed
curl -sS -X POST localhost:8080/api/migrations/analyze -H 'Content-Type: application/json' \
  -d '{"connectionId":"<id>","sql":"not sql"}' | jq '.verdict, .safeToRun'
```

Expected: `SAFE`; `DANGER` + `true`; two lock entries; `UNKNOWN` + `false`.

- [ ] **Step 3: Verify authorization with a second user**

As a user with no grant on that connection, the same call must return 403 — not 200,
and not a 500 from a swallowed `ResponseStatusException`.

- [ ] **Step 4: Update CLAUDE.md**

Add a section documenting the analyzer: that it is deterministic by convention, that the
rule table is engine-verified, the JSqlParser 5.2 shim and why it exists, the `now()`
STABLE finding, and that FK locks both tables.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document pre-flight migration review in CLAUDE.md"
```

---

## Self-Review

**Spec coverage:** pipeline (T1, T2, T4), fail-closed (T1 garbage test, T4 service test,
T6 curl), rule table incl. FK/CHECK/NOT VALID (T2), per-table locks (T2, T3), duration
buckets (T2), placement via `DatabaseDialect` (T4), MySQL unsupported (T4),
authorization (T4, T6), 3-layer verification (T1/T2 unit, T3 engine, docsUrl in T2),
MCP release checklist (T5). No gaps.

**Type consistency:** `DdlFacts` field order is identical in every construction;
`MigrationRiskReport` is built only through `report(...)` in the Postgres provider and
explicitly in the two fail-closed paths; `TableFacts(rowEstimate, sizeBytes, empty)`
consistent throughout; `LockRef(table, mode, blocks)` consistent.

**Known deviation to watch:** `MigrationRiskService.tableFacts` casts `request` to
`ConnectionRequest` — the implementer should type the parameter properly once the exact
`CredentialService` return type is confirmed at the call site.
