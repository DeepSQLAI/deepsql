# Twelve endpoints authorized the wrong id

*Found 2026-09-10 in a repository-wide security audit; reproduced live against the running
stack 2026-09-16. Severity: high (cross-tenant read, write and delete).*

## What was wrong

Every one of these endpoints *had* an access check — which is why the safety scanner passed
them — but the check verified a **different id** than the one the endpoint operated on.

Two shapes:

**The check was on a caller-supplied connection, the operation on an unrelated row.** Every
scan endpoint took a `@RequestParam connectionId` beside a `@PathVariable sourceId`/`jobId`,
and asserted on the param:

```java
@DeleteMapping("/sources/{sourceId}")
public ResponseEntity<Void> deleteSource(@PathVariable String sourceId,
                                         @RequestParam("connectionId") String connectionId) {
    accessControlService.assertCanManageConnectionContent(connectionId);  // checks connectionId
    codeScanService.deleteSource(sourceId);                               // deletes sourceId
}
```

The caller controls both. They pass a connection they *do* own (so the check passes) and a
`sourceId` belonging to a **different** tenant. The service resolves it by `findById(sourceId)`
with no ownership filter, and the operation lands on a row they have no access to.

**The check was made conditional on a field the attacker controls.**
`CompanyKnowledgeController.update` ran its guard only when the body carried a `connectionId`:

```java
if (entry.getConnectionId() != null && !entry.getConnectionId().isBlank()) {
    accessControlService.assertCanManageConnectionContent(entry.getConnectionId());
}
```

So the attacker simply **omits the field**. The guard is skipped entirely and
`updateEntry(entryId, ...)` overwrites any tenant's entry. The service even *rejects* a
mismatched `connectionId`, so supplying it correctly triggers the check while omitting it
skips both — the safe-looking validation is exactly what makes omission the best move.

The twelve:

| Controller | Endpoints | Unchecked id |
|---|---|---|
| `CodeScanController` | `PUT /sources/{id}/focus`, `DELETE /sources/{id}`, `POST /sources/{id}/scan`, `GET /jobs/{id}`, `GET /sources/{id}/jobs`, `GET /jobs/{id}/stream`, `POST /suggestions/{id}/decide`, `POST /suggestions/bulk-decide` | `sourceId` / `jobId` / `suggestionId` (bulk: a whole list) |
| `CompanyKnowledgeController` | `PUT /{entryId}`, `DELETE /{entryId}` | `entryId` |
| `DashboardAlertController` | `PUT /{alertId}`, `DELETE /{alertId}` | `alertId` (dashboard authorized, alert not bound to it) |

The dashboard-alert pair is the "two path variables" case: `requireDashboard(dashboardId)`
authorized the dashboard correctly, but `updateAlert(alertId)` / `deleteAlert(alertId)` acted on
an alert never checked to belong to that dashboard. Pair a dashboard you own with another
tenant's `alertId` and you repoint or delete their alert.

## Reproduced live, not inferred

Two tenants on the running stack: `analyst` holds a grant on **Demo Shop** only; `admin` owns
**QA Vault Copy**. Victim rows (a scan source, a knowledge entry, a dashboard alert) were
planted on QA Vault Copy, then `analyst` attacked each by passing their *own* connection/dashboard
beside the victim's row id:

| Attack | Unpatched | Patched |
|---|---|---|
| Delete another tenant's scan source | **200**, `active` t→f | **404**, survives |
| Delete another tenant's knowledge entry | **200**, row 1→0 | **404**, survives |
| Overwrite another tenant's entry via PUT with no `connectionId` | **200**, title → "PWNED by analyst" | **404**, unchanged |
| Delete another tenant's alert via own dashboard + their alertId | **200**, alert 1→0 | **404**, survives |

And the fix does not break legitimate access, also verified live: `analyst` deletes a source on
their *own* granted connection (200), and `admin` reads QA Vault Copy's rows (200).

## The fix

Resolve the row's **own** connection and authorize against that; never trust the
caller-supplied id.

- `CodeScanService` gained `findConnectionIdForSource/Job/Suggestion`; the controller asserts
  through `assertCanManage{Source,Job,Suggestion}` helpers.
- `CompanyKnowledgeService.findConnectionIdForEntry`; the controller asserts
  unconditionally on the entry's connection (the `connectionId` field is no longer consulted
  for the check).
- `DashboardAlertService.findDashboardIdForAlert`; the controller binds the alert to the
  already-authorized dashboard with `requireAlertOnDashboard`.

Two rules held throughout:

- **404, not 403**, for both "no such id" and "not yours" (`assertCan...OrNotFound`, and the
  alert-binding throws `NOT_FOUND`), so the endpoint is not an existence oracle — the caller
  cannot tell a row they may not touch from one that does not exist. This matches
  `DashboardWorkspaceService.assertCanReadDashboard`.
- **`bulk-decide` checks every id, and an id that resolves to nothing fails too**, so an unknown
  id cannot ride into an otherwise valid batch.

The `connectionId` params are still accepted (wire compatibility) and ignored, noted at each
site so nobody re-wires them.

## Why the scanner missed it, and why it still does

`ConnectionScopedAuthorizationSafetyTest`'s `AUTHORIZED` check is presence-only: it asks whether
*some* `assertCan...` call appears in the handler body, not *which id* it verifies. All twelve
contained an assert, so all twelve passed. A general dataflow scanner that proved "the assert's
argument is derived from the operated-upon id" is a much larger undertaking than this fix and is
not attempted here; the durable protection is the live cross-tenant test above, which is the
same standard the audit itself used. The safety test still passes because the new helpers
contain `assertCan...`, which is correct — they now assert on the resolved connection.

## Verification

| Step | Result |
|---|---|
| Live cross-tenant attacks, 4 variants | **200 unpatched → 404 patched**, every one |
| Legitimate access (own connection; admin on own rows) | **200**, unchanged |
| Backend suites | **32 tests, 0 failures** |
| `mvn compile` | clean |

Both users' password hashes and every planted row were restored; the database is back to its
prior state (all three tables empty as they were).

## Residual work

- **`DashboardAlertController` returns 500, not 404, for a non-existent `dashboardId`.**
  `requireDashboard` throws `IllegalArgumentException("Dashboard not found")`, and the delete
  handler's catch block maps only `ResponseStatusException` and the generic `Exception` — so the
  missing-dashboard case falls through to 500. Surfaced by this QA but pre-existing and separate
  from the wrong-id fix; it deserves its own change (map `IllegalArgumentException` to 404, as
  the update handler already does).
- A dataflow-aware version of the authorization scanner would catch this class structurally.
  Worth doing, but a research-shaped task rather than a fix.
