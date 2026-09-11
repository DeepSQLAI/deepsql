# Binding public dashboard queries to their published shapes

*2026-09-11. Addresses a critical finding from the 2026-09-10 repository security audit.*

## The problem

`PublicDashboardController.query` accepts the SQL to run as a caller-supplied body field:

```java
public record PublicQueryRequest(String sql, Integer limit) { }
```

It is never compared against the dashboard being shared. The only check is
`validateReadOnlySql`, which asks whether the statement *reads* — not whether it is a
query this dashboard was ever meant to run.

So a share link created to publish one chart actually grants **anonymous, unauthenticated
read of every table on that connection**, paginable to completion:

```bash
curl -X POST https://host/api/public/dashboards/$TOKEN/query \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT * FROM users","limit":5000}'
```

Share tokens are 24 bytes of `SecureRandom` (192 bits), so this is not brute-forceable —
the exposure is to whoever receives or forwards a link, which is precisely the population a
share link is expected to be safe for.

A second, independent defect: the public path passes
`QueryExecutionContext.api("public-share")`, a username with no
`connection_chat_access_policy` row, so `resolveEffectivePolicy` returns
`EffectivePolicy.none()`. **Column-level protections and PII redaction do not run at all on
the public path**, even where the connection has an active policy.

## Constraint: public dashboards are interactive by design

The obvious fix — store the dashboard's queries and require an exact string match — breaks a
documented feature. `agent/skills/dashboard-design/SKILL.md` instructs the agent to build
date pickers and dropdowns that re-query on change (`:148`), sharing state through
`window.__dateRange`, and states plainly (`:54`):

> There is **no placeholder convention**. You write normal SQL strings in JS and pass the
> finished string to `deepsql.query`.

So the exact string is not knowable ahead of time: every date-range change produces a
different one. `PublicDashboardPage` also supports kiosk auto-refresh, so public queries
must stay repeatable.

An exact-match allowlist would therefore break interactive dashboards **only on the public
link** — working for the author, failing for the audience. That is the worst shape a
regression can take.

## Design: match the query's *shape*, not its text

At share time, extract every SQL string the artifact can issue, normalize each to a shape,
and store the set of fingerprints. At query time, fingerprint the incoming SQL and reject
anything with no stored match.

A shape is the query with literals replaced by placeholders. `QueryNormalizer` (already in
the codebase, used by `QueryFingerprintService`) does exactly this: string literals, numbers,
booleans and `IN` lists collapse to `?`. Two queries differing only in a date range share a
shape; two queries naming different tables or columns do not.

### Verified behaviour

Tested against a faithful port of the real `QueryNormalizer` patterns
(`QueryNormalizer.java:17,18,28,30`), with the published shape being
`SELECT COUNT(*) FROM public.properties p WHERE p.created_at BETWEEN '…' AND '…'`:

| Incoming query | Result |
|---|---|
| Same query, different date range | matches — interactivity preserved |
| Whitespace, newline and case variations | matches |
| `SELECT * FROM users` | blocked |
| Same shape, different table (`public.users`) | blocked |
| Same shape, different column (`password`) | blocked |
| Same query with `OR 1=1` appended | blocked |
| Escaped-quote `UNION` smuggled inside a literal | blocked |

The last case is worth recording. The payload
`'2026-03-01 '' UNION SELECT password FROM users --'` normalizes to `… between ? and ??` —
two placeholders, not one — because the `'[^']*'` pattern does not model SQL's `''` escape
and so splits the literal differently than the database would. The shape changes, so the
fingerprint misses and the query is refused. **The imprecision fails in the safe direction:**
any attempt to smuggle structure through a literal perturbs the shape.

### Extraction happens at share time, from the stored artifact

`saved_dashboards.dashboard_config` holds the whole artifact as one self-contained HTML
document, with the queries embedded in `<script>` blocks as JS template literals. The SQL is
therefore statically present; it just carries interpolation syntax:

```js
`SELECT COUNT(*) FROM public.properties p WHERE p.created_at BETWEEN '${from}' AND '${to}'`
```

Replacing each `${…}` with a placeholder before normalizing maps JS interpolation onto the
same abstraction the normalizer already applies to literals. A prototype over a realistic
artifact extracted all four `deepsql.query` call sites across backtick, double- and
single-quoted forms, and the resulting fingerprint for the query above was **byte-identical**
to the one produced by normalizing the runtime SQL. Publish-time extraction and query-time
normalization agree, which is the property the whole design rests on.

This is preferred over capturing shapes from the author's first render: it needs no extra
step, cannot produce a partly-captured set, and a dashboard's allowed shapes are re-derivable
from its stored config at any time.

### Unmatched shapes fail closed

A query whose shape was not extracted is refused. The artifact already renders a per-widget
error on a rejected query, so one unmatched widget degrades alone and the rest of the
dashboard keeps working — the failure mode the runtime is already built for.

## Scope

**In:**
- Extract query shapes from the artifact at share time; persist them with the dashboard.
- Enforce the shape match in `PublicDashboardController.query`.
- Resolve a real policy identity for public callers so column protections and redaction apply.

**Out:**
- The missing `dashq` nginx rate limiter (a separate finding; an nginx change with its own
  blast radius).
- The internal authenticated bridge `POST /api/dashboards/query`, which is access-checked
  per connection and is not anonymous.

## Defence in depth

The shape gate is a new primary control, not a replacement. `validateReadOnlySql`,
`connection.setReadOnly(true)`, the row cap and the `is_public` re-check all remain. This
matters because `QueryNormalizer` was written for analytics grouping, where a collision is a
cosmetic nuisance; used as a security boundary, a collision would be a vulnerability. It is
one layer among several, and is deliberately not the only thing standing between an
anonymous caller and the database.
