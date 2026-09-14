# A SELECT could write through both read-only layers

*Found 2026-09-10 in a repository-wide security audit; reproduced against a live PostgreSQL
2026-09-11. Severity: critical.*

## What was wrong

Both of the product's read-only defences were bypassed by one statement:

```sql
SELECT dblink_exec('dbname=app user=postgres host=127.0.0.1', 'DELETE FROM orders')
```

**The guard failed** because `McpSqlGuardService` classifies by statement *verb*. The statement
begins `SELECT`, which is on `ALLOWED_READ_ONLY_KEYWORDS`, and none of the 15
`FORBIDDEN_SQL_KEYWORDS` appears anywhere in it. `dblink_exec` is a *function call* — invisible
to a verb-based parser.

**`connection.setReadOnly(true)` failed** for a subtler reason, and this is the part worth
understanding: `dblink` opens a **new outbound connection** to the database. That second
connection runs its own transaction, which is not read-only. The read-only flag constrains the
session it is set on; it cannot constrain a session the query itself dials out and creates.

CLAUDE.md describes `setReadOnly(true)` as the backstop that "keeps the *next* parser gap from
becoming data loss". That holds for ordinary writes — `SELECT … INTO`, `nextval`, `lo_import`
and volatile writer functions are all correctly refused by it. It does not hold for a function
that leaves the session.

## Reproduced, not inferred

Against a real PostgreSQL, in an isolated `zz_sec` schema (created and dropped for the test):

```
rows before                                     3
BEGIN TRANSACTION READ ONLY
SELECT dblink_exec('dbname=dba_agent …','DELETE FROM zz_sec.victim')
 dblink_exec
-------------
 DELETE 3
COMMIT
rows after                                      0
```

A `DELETE` ran to completion inside an explicitly read-only transaction.

The same class of function reads the database server's filesystem, also under read-only:

```
BEGIN TRANSACTION READ ONLY
SELECT length(pg_read_file('/etc/hostname'))   ->  13
```

And the guard permitted every one of them. Running the shipped `validateReadOnlySql` over the
payloads directly returned `ALLOWED` for `dblink_exec`, `dblink`, `pg_read_file`, `pg_ls_dir`,
`pg_read_binary_file`, `lo_import` and `LOAD_FILE`.

Until public dashboard queries were bound to their published shapes, this was reachable from
the **unauthenticated** share endpoint, which runs through the same executor.

## The fix

A denylist of functions that read or write outside the session, checked after the verb checks
in both guards:

```java
private static final List<String> DANGEROUS_SQL_FUNCTIONS = List.of(
    "dblink", "dblink_exec", "dblink_connect", "dblink_open", "dblink_send_query",
    "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
    "lo_import", "lo_export",
    "load_file");
```

A denylist is usually the wrong shape. It is the right shape *here* because the guard's
allowlist governs **verbs**, and there is no allowlist of functions that may appear inside a
`SELECT` — the set of legitimate functions is open-ended, while the set that escapes the
session is small and nameable.

**Matched as a call, not as a name.** The pattern requires the name, optional whitespace, then
an open paren, with a leading boundary check:

```java
"(?<![\\w$.])(" + DANGEROUS_FUNCTION_ALTERNATION + ")\\s*\\("
```

Matching the bare name would reject ordinary identifiers — a `dblink_audit` table, a
`load_file_name` column — which is exactly the mistake CLAUDE.md records for the old
`\bCOMMENT\b` rule that rejected `SELECT * FROM comment`. The boundary also stops a different
function such as `my_dblink(` from matching. Inspection runs on text with comments and string
literals already stripped, so neither `/*x*/dblink_exec(` nor a name inside a quoted literal
can hide or falsely trigger a match.

## Both guards, or neither

`McpSqlGuardService.java` and `mcp/deepsql-phase1-lib.js` are a functional mirror of each
other. A statement one blocks and the other allows *is* the bypass, so the change landed in
both and parity is verified directly: 20 payloads — 14 attacks, 6 legitimate queries including
the identifier false-positives — run through both implementations, **0 mismatches**.

## Verification

| Step | Result |
|---|---|
| Tests before the fix (RED) | 5 failures, all "expected false but was true" |
| Tests after the fix (GREEN) | 19 pass |
| Denylist stubbed to `return null` (mutation) | 5 fail again — the tests guard the fix |
| Java/JS parity over 20 payloads | 0 mismatches |
| Live attack replayed after the fix | blocked; table still 3 rows, unchanged |
| Backend suites | 82 tests, 0 failures |
| MCP suite | 272 tests, 0 failures |

The `zz_sec` schema and the `dblink` extension created for this test were dropped; the database
is back to its prior state.

## Residual work

- **`ExplainPlanService` opens its own connection and never calls `setReadOnly(true)`** — a
  `grep` for `setReadOnly` over `src/main/java` returns exactly one hit, in
  `QueryExecutorService`. The guard now covers the function class on that path too, but the
  database-level backstop is still absent there.
- **`COPY … FROM/TO PROGRAM`** is blocked today by the `COPY` verb being on the forbidden list,
  not by this denylist. That is sufficient, but it means the protection depends on a verb rule
  rather than the function rule, which is worth knowing if the verb list is ever narrowed.
- Revoking `EXECUTE` on these functions from the connection role, and not provisioning
  superuser connection users, remains the stronger control. The guard reduces blast radius; it
  does not replace database-level permissions.
