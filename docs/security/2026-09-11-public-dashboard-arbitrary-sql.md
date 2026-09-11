# A public share link granted full database read

*Found 2026-09-10 in a repository-wide security audit. Severity: critical.*

## What was wrong

`PublicDashboardController.query` took the SQL to run as a caller-supplied body field:

```java
public record PublicQueryRequest(String sql, Integer limit) { }
```

and never compared it against the dashboard being shared. The only check was
`validateReadOnlySql`, which asks whether a statement *reads* — not whether it is a query
this dashboard was ever published to run.

The endpoint is `permitAll` (via `/public/**` in `SecurityConfig`), so a link created to
publish one chart granted **anonymous, unauthenticated read of every table on that
connection**, paginable to completion:

```bash
curl -X POST https://host/api/public/dashboards/$TOKEN/query \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT * FROM information_schema.tables","limit":5000}'

curl -X POST https://host/api/public/dashboards/$TOKEN/query \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT * FROM users","limit":5000}'
```

Share tokens are 24 bytes of `SecureRandom` — 192 bits, so this was never brute-forceable.
The exposure is to whoever receives or forwards a link, which is exactly the population a
share link is supposed to be safe for. The class javadoc called this "the accepted trade-off
of a public BI link", but the trade-off as documented is *view the dashboard*; the
implemented behaviour was *read the whole connection*.

## Why an exact match could not be the fix

Public dashboards are interactive by design. `agent/skills/dashboard-design/SKILL.md`
instructs the agent to build date pickers and dropdowns that re-query on change (`:148`),
sharing state via `window.__dateRange`, and states plainly (`:54`):

> There is **no placeholder convention**. You write normal SQL strings in JS and pass the
> finished string to `deepsql.query`.

So the exact string is not knowable at publish time — every date-range change produces a new
one. `PublicDashboardPage` also supports kiosk auto-refresh, so queries must stay repeatable.

Exact matching would therefore break interactive dashboards **only on the public link**:
working for the author, failing for the audience. That is the worst shape a regression can
take, so it was rejected.

## The fix: match the shape, not the text

`DashboardQueryShapeService` extracts every query the artifact can issue, normalizes each to
a *shape* — the statement with literals replaced by placeholders, via the existing
`QueryNormalizer` that already backs `QueryFingerprintService` — and requires an incoming
query to match one of them.

Two queries differing only in a date range share a shape. Two naming different tables or
columns do not.

| Incoming query | Result |
|---|---|
| Same query, different date range | allowed — interactivity preserved |
| Whitespace, newline and case variations | allowed |
| `SELECT * FROM users` | refused |
| Same shape, different table | refused |
| Same shape, different column | refused |
| Same query with `OR 1=1` appended | refused |
| Escaped-quote `UNION` smuggled inside a literal | refused |

The last row is worth recording, because it is refused for a non-obvious reason. The payload
`'2026-03-01 '' UNION SELECT password FROM users --'` normalizes to `… between ? and ??` —
**two** placeholders, not one — because the `'[^']*'` rule does not model SQL's `''` escape
and so splits the literal differently than the database would. The shape changes, the
fingerprint misses, the query is refused. **The imprecision fails in the safe direction:** any
attempt to smuggle structure through a literal perturbs the shape.

### Extraction is static, at share time

`saved_dashboards.dashboard_config` stores the artifact as one self-contained HTML document
with its queries embedded in `<script>` blocks as JS template literals, so the SQL is
statically present — it just carries interpolation syntax:

```js
`SELECT COUNT(*) FROM public.properties p WHERE p.created_at BETWEEN '${from}' AND '${to}'`
```

Each `${…}` is replaced with a placeholder before normalizing, mapping JS interpolation onto
the same abstraction the normalizer applies to literals. A test asserts directly that the
shape extracted from the artifact equals the shape of the SQL issued at runtime — that seam
is what the whole design rests on, so it is pinned rather than assumed.

This was chosen over capturing shapes from the author's first render: it needs no extra step,
cannot produce a partly-captured set that breaks a link for its audience, and a dashboard's
allowed shapes stay re-derivable from its stored config.

### Unmatched shapes fail closed

A query whose shape was not extracted is refused with `This query is not part of the shared
dashboard.` The artifact already renders a per-widget error on a rejected query, so one
unmatched widget degrades alone and the rest of the dashboard keeps working.

## Second defect: a policy added after sharing did not apply

Enabling a public share is refused while the connection has an active chat-access policy
(`SavedDashboardController:81`) — but nothing re-checked afterwards. A link created *before* a
policy was added stayed live, and on that path `QueryExecutionContext.api("public-share")`
carries a username with no policy row, so `resolveEffectivePolicy` returns
`EffectivePolicy.none()` and `enforcePreExecution` returns at its `protectsAnything()` check
without inspecting anything. Column-level protections and PII redaction therefore never ran.

This is narrower than "public callers bypass all policies" — the normal flow cannot create the
combination — but it is a real time-of-check/time-of-use gap. `PublicDashboardController` now
re-checks `hasActivePolicy` per query, for the same reason it already re-checks `is_public`:
revocation has to reach an already-issued link.

## Defence in depth

The shape gate is a new primary control, not a replacement. `validateReadOnlySql`,
`connection.setReadOnly(true)`, the row cap and the `is_public` re-check all remain. This
matters because `QueryNormalizer` was written for analytics grouping, where a shape collision
is a cosmetic nuisance; used as a security boundary a collision would be a vulnerability. It
is deliberately one layer among several.

## Verification

| Step | Result |
|---|---|
| Tests before the service existed (RED) | compilation failure — class not found |
| Tests after the fix (GREEN) | 13 pass |
| `matches()` stubbed to `return true` (mutation) | 5 fail — the exfiltration and fail-closed cases |
| Restored | green again |
| Related suites (authorization, workspace, normalizer) | 39 tests, 0 failures |
| `mvn compile` | clean |

```bash
cd backend && mvn test -Dtest=DashboardQueryShapeServiceTest
```

## Residual work

The public query path still has **no rate limit**. `docker/nginx/default.conf` declares only
the `sqlexec` zone, scoped to `^/api/connections/[^/]+/query$`, which does not cover
`/api/public/dashboards/`. CLAUDE.md claimed a `dashq` limiter existed; it never did. Shape
binding means an anonymous caller can now only re-run the dashboard's own queries, which bounds
the damage considerably, but a public `pg_sleep`-shaped widget or a heavy aggregate can still be
hammered. Tracked separately — it is an nginx change with its own blast radius.
