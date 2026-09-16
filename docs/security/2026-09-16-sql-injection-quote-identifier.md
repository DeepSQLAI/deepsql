# SQL injection through an unescaped identifier quoter

*Found 2026-09-10 in a repository-wide security audit; reproduced against a live PostgreSQL
2026-09-16. Severity: high.*

## What was wrong

Table and column names cannot be bind parameters, so they are interpolated into SQL by hand
and protected by quoting. `CardinalityEstimationService` quoted them like this:

```java
private String quoteIdentifier(String dbType, String identifier) {
    if ("postgres".equals(dbType)) {
        return "\"" + identifier + "\"";
    } else {
        return "`" + identifier + "`";
    }
}
```

It wraps, and never **doubles** an embedded quote. That is exactly as protective as
`"'" + value + "'"` is for a string literal: the attacker's own quote closes the identifier and
everything after it is live SQL.

Six sinks consume it, none with a bind parameter — `CardinalityEstimationService.java:166`,
`:171`, `:201`, `:241`, `:298`, `:326`, all `String.format` into `jdbc.queryForObject` /
`queryForList`.

The tainted value is a path variable, unvalidated from the edge:

```
POST /api/brain/statistics/{connectionId}/tables/{tableName}
  BrainController.java:1947   @PathVariable String tableName
  -> cardinalityEstimationService.collectTableStatistics(connectionId, tableName)
  -> quoteIdentifier(dbType, tableName)
  -> String.format("SELECT COUNT(*) FROM %s", quotedTable)
```

## Reproduced, not inferred

In an isolated `zz_v` schema created and dropped for the test, with the payload passed as the
`tableName` path variable:

```
victim" AS t; DROP TABLE zz_v.probe; SELECT 1 FROM zz_v."victim
```

the quoter produced, and PostgreSQL executed:

```sql
SELECT COUNT(*) FROM zz_v."victim" AS t; DROP TABLE zz_v.probe; SELECT 1 FROM zz_v."victim"
```

```
probe before: 1
DROP TABLE
probe after : 0
```

No errors at all — the count returned, the table was dropped, the trailing select returned its
rows. The payload contains no `/`, so Spring's `StrictHttpFirewall` (which rejects `%2F`) does
not stand in its way.

**There is no second line of defence on this path.** It never reaches
`QueryExecutorService`, so it gets no `connection.setReadOnly(true)`, no policy service and no
row cap — a `grep` for `setReadOnly` over `src/main/java` returns exactly one hit, and it is
not here.

## It was an outlier, not a convention

Sweeping every identifier quoter in the backend rather than trusting the reported count:

| Quoter | Escapes? |
|---|---|
| `PostgresSamplingProvider:21` | yes |
| `MySQLSamplingProvider:21` | yes |
| `PostgresIntrospectionProvider:953` | yes |
| `SlackDailyDigestService:3028` | yes |
| `ColumnValueCollectionService:450` | **no** |
| `CardinalityEstimationService:501` | **no** |

Four of six were already correct. The pattern is worth noting: the three **provider** classes
— written by whoever was thinking about dialects — all escape. The **service** classes that
reimplemented the same primitive later got it wrong, which is the same "clustered by when it
was written" signature the `BrainController` authorization misses had.

`SlackDailyDigestService` is a near miss worth recording: it escapes via
`identifier.replace(quote, quote + quote)` with a *variable* rather than a literal, so a first
grep flagged it as vulnerable. Reading it settled that it is safe. A pattern-matched audit
produces false positives as readily as false negatives.

## The fix

One shared `SqlIdentifier` utility, with both broken copies delegating to it:

```java
public static String quote(String identifier, String dbType) {
    String quote = isMysql(dbType) ? "`" : "\"";
    return quote + identifier.replace(quote, quote + quote) + quote;
}
```

Centralised rather than patched in place, because two copies of a security primitive is the
defect: one gets fixed and the other is missed. The same reason the SQL guard is kept mirrored
between Java and JS, and the two Agent-chat renderers now share one escape.

A second layer refuses what should never reach SQL at all:

```java
private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.]+");
```

Escaping alone makes injection impossible but still lets a caller address an object the
feature never meant to touch. `requireSafe` runs at the **top** of `collectTableStatistics`,
before `getDecryptedConnection` — validating after it would make a hostile name a
credential-use primitive even when the statement never runs, which is the "check before the
work, not after" rule the slow-query analytics endpoints already learned.

The pattern is deliberately permissive enough for real schemas (`v_daily_revenue`,
`order_items_2026`, `public.orders`, `tableName$`). A validator that rejects legitimate names
is one the next person deletes.

`BrainController` now returns **400** for a rejected name rather than letting the catch-all
report 500 — a bad request should not read as an outage.

## Verification

| Step | Result |
|---|---|
| Tests before the utility existed (RED) | compilation failure — symbol not found |
| Tests after the fix (GREEN) | 10 pass |
| Escaping stubbed out (mutation) | 3 fail — the tests guard the fix |
| Live DB, vulnerable quoter | `DROP TABLE` ran; probe **1 → 0** |
| Live DB, fixed quoter | refused; probe **1 → 1** |
| Backend suites | **104 tests, 0 failures** |
| `mvn compile` | clean |

The fixed path's own error message is the proof of why it is safe:

```
ERROR: relation "zz_v.victim" AS t; DROP TABLE zz_v.probe; SELECT 1 FROM zz_v."victim" does not exist
```

PostgreSQL read the whole payload as **one table name**, not three statements.

The `zz_v` schema created for this test was dropped; the database is back to its prior state.

## A note on the test that was wrong first

The first version of `theInjectedStatementCollapsesIntoASingleIdentifier` asserted
`!sql.matches(".*\"\\s*;.*")` and **failed against correct output**, because `"";` is an
*escaped* quote followed by a semicolon *inside* the identifier — textually close to a
terminator and semantically its opposite. That is precisely the confusion the vulnerable
quoter made. It now asserts on the parse property (only the closing quote is unescaped),
backed by the live-database result above.

## Residual work

- `ColumnValueCollectionService` is fed catalog-derived names today, so it was not exploitable
  — but it was one caller away, which is why it was fixed rather than noted.
- Other `String.format`-built SQL in the brain services should be swept for the same shape.
  This PR fixes the proven-exploitable path and the identical copy beside it; a broader sweep
  is a separate change with its own blast radius.
