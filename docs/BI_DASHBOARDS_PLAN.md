# DeepSQL BI / Shareable Dashboards — Day-One Self-Host Plan

> Status: design. Grounded in the existing (orphaned) dashboard code + the current
> data-access/security model. Goal: customers build dashboards with the DeepSQL
> agent and share read-only links (expiry + password) with *their* customers,
> shipped in the self-host package from day one.

## 1. Positioning (why this wins)

Charts are commoditized. The moat is that **DeepSQL's brain is the semantic layer**
BI tools spend months hand-modeling (schema understanding, business rules, inferred
relationships, verified query patterns). So the agent can build *correct* dashboards
from natural language with **no LookML / no modeling step**. Pitch: *"Point DeepSQL
at your DB, ask for a dashboard in English, share the link."* This is a wedge into
embedded analytics (Metabase/Omni/Luzmo territory) with a real, already-built moat.

## 2. What already exists (reuse, don't rebuild)

**Chassis (works, just orphaned behind `SHOW_DASHBOARD_TAB=false`):**
- `SavedDashboard` entity + `SavedDashboardController` — full CRUD, folders, favorites,
  search; `dashboardConfig` is a JSON spec scoped to (connectionId, userId).
- `dashboardConfig` schema: `inputs[]` (params), `metrics[]` (KPI cards), `charts[]`
  (`type` bar/line/pie/area, `query`, `xAxis`/`yAxis`/`series`/`colors`/`height`/`limit`),
  `tables[]`.
- Frontend: `DashboardBuilder` (recharts renderer for all chart types), `SavedDashboardsPanel`,
  `useDashboardStore`. **`recharts` already a dependency.**
- Query exec path: `QueryExecutorService` + `QueryExecutionPolicyService.enforce()` with
  `QueryExecutionContext` (READ_ONLY_ONLY mode), jsqlparser statement classification,
  JDBC statement timeout (30s default / 600s max), optional `LIMIT`, and
  `SqlExecutionAuditService.record()` for every query.
- Public-token primitive: `McpTokenService` (`publicId.secret`, SHA256-hashed, expiry,
  revoke) — the cleanest base for share links. SecurityConfig whitelists public paths.

**Net-new (the real work):**
1. The **agent → dashboard** generation layer — *never actually built* (the old
   `DashboardBuilderEffect` is a cosmetic animation; there's no LLM→config endpoint).
2. A **read-only guarantee at the data layer** — no read-only role/replica exists.
3. **Bound parameters** — the old config uses `{{param}}` *string substitution*
   (SQL-injection vector); unacceptable for sharing.
4. The **sharing layer** — links, expiry, password, revoke, per-viewer row scoping.
5. **Abuse/cost controls** on public endpoints — none today.

## 3. Core principles

1. **Read-only by construction**, defense-in-depth (§4). The data plane physically
   cannot write, regardless of stored credentials.
2. **Server-stored queries only.** Viewers supply *parameter values*, never SQL.
3. **Bound parameters, never string interpolation.**
4. **Grounded by the brain.** The agent uses get_brain_context / relationships /
   business rules so generated queries are semantically correct, and **shows the SQL
   + provenance for confirm-before-save** (this is how we avoid the old "wrong chart"
   failure that got the feature pulled).
5. **Self-host day-one.** Everything runs in the existing compose package; the public
   viewer is a no-login route served by the same frontend/backend.

## 4. Read-only model (layered — the crux)

Today the backend connects with the customer's credentials, which **may be read-write**,
and there is no read-only role/replica. So we layer guarantees, weakest→strongest:

- **L1 — App statement gate (exists, reuse).** Add a `DASHBOARD` / `SHARED_BI` origin to
  `QueryExecutionContext` that is *always* `READ_ONLY_ONLY` with **no admin override**.
  jsqlparser rejects anything but SELECT/WITH/EXPLAIN. Already the mechanism chat/MCP use.
- **L2 — Read-only JDBC + transaction.** Run dashboard queries on a connection with
  `connection.setReadOnly(true)` inside an explicit read-only transaction
  (`SET TRANSACTION READ ONLY` on PG / equivalent), tight `setQueryTimeout`, hard row cap.
- **L3 — Bound parameters + server-stored queries.** The stored query is a template with
  *named bind params*; values bind as JDBC `?`. Viewers cannot inject SQL or change the
  query text.
- **L4 — Dedicated read-only DB credential (recommended; net-new).** Add an **optional
  second credential per connection — the "BI / read-only credential"** — used *exclusively*
  by the dashboard + shared paths. This is the gold standard: even an app bug can't write
  because the DB role lacks the grant. If absent, fall back to L1–L3.

**Decision:** L1–L3 are mandatory and make the feature safe. **L4 is required before
external sharing of real (non-playground) data** — make it a one-time setup step
("create a read-only DB user → paste it as the BI credential"), with a "verify read-only"
probe. For the launch/playground, L1–L3 on the sample DB is sufficient (zero risk).

## 5. Data model changes

- **`SavedDashboard.dashboardConfig` → v2:** each chart/metric/table query becomes a
  template `{ sql, params: [{name, type, required, default, options?}] }`. No `{{}}`
  string substitution. Add `schemaVersion`. Migrate the old shape on read.
- **`DatabaseConnection`:** add optional `biCredentialId` (a second encrypted credential,
  reusing `CredentialService`/`EncryptionService`) + `biReadOnlyVerified` flag.
- **New `SharedDashboardLink`** (model on the `McpToken` pattern):
  `id, dashboardId, token(publicId.secret, store SHA256 hash), createdBy, createdAt,
  expiresAt?, passwordHash?(bcrypt), maxViews?/currentViews, lockedParams(jsonb — server-set
  values the viewer can't change, e.g. tenant_id), isRevoked, lastAccessedAt, lastAccessedIp`.

## 6. Agent-built dashboards (P1 — the magic) — WEB SURFACE ONLY

**Scope decision:** dashboards are a **web UI feature**, *not* MCP tools. Building a
dashboard is inherently visual (you watch it render, tweak it, share it) — an editor
agent (Claude Code/Cursor) emitting dashboard JSON over MCP isn't the real use case.
So generation runs **server-side, driven by the web Agent tab**, with no new MCP surface.

- **`DashboardGeneratorService`** (backend): NL prompt + connectionId → brain consult
  (`get_brain_context`, `get_schema`, `list_business_rules`, `get_relationships`) → LLM
  emits a validated v2 `dashboardConfig`. Validate every query with the existing
  `validateReadOnlySql` (SELECT/WITH-only, single statement, no data-modifying CTEs),
  references only known tables/columns, parameterized. Business rules applied
  (e.g. cancelled filter) automatically.
- **Web flow:** re-enable the dashboard surface in the current app; the **web Agent tab**
  calls `POST /dashboards/generate` → streams tiles into the renderer →
  **preview with SQL + grounding shown** → user confirms → save via the existing
  `SavedDashboard` CRUD. Reuse the recharts renderer untouched.
- **No MCP tools** for dashboards (the `create_dashboard`/`list_dashboards`/`get_dashboard`
  tools added in 0.27.0 were reverted — kept off the agent tool surface deliberately).

## 7. Sharing & gating (P2)

- **Public endpoints (whitelisted in SecurityConfig, no login):**
  - `POST /public/dashboards/{token}/unlock` — checks password (if set), expiry, revoke,
    max-views; issues a short-lived viewer JWT scoped to that dashboard.
  - `GET /public/dashboards/{token}` — returns the dashboard config (chart specs, declared
    inputs) — **never the raw credentials or full schema.**
  - `POST /public/dashboards/{token}/data` — runs the server-stored queries with bound
    params (viewer-supplied input values validated against declared types/options +
    `lockedParams` merged in server-side), via the L1–L4 read-only path. Rate-limited.
- **Gating:** expiry, optional password, max-views, instant revoke — all on the link.
- **Per-viewer row scoping (P3):** `lockedParams` (e.g. `tenant_id`) bind into every query's
  WHERE as a parameter the viewer can't see or change → true multi-tenant embedded analytics.
  Optionally auto-derive from `ConnectionAnalyticsConfig.tenantColumn`.

## 8. Abuse / cost controls (public surface)

- **Rate limit** per token + per IP (new filter; Redis sliding window — Valkey is already
  in the stack). 429 on exceed.
- **Result caching** keyed by (dashboardId, bound-param values) with a TTL → a viral link
  doesn't hammer the customer DB; reuse the Redis tier / `query_plan_cache` idea.
- **Hard caps:** statement timeout (e.g. 15–30s), row cap, max charts/queries per render.
- **Audit:** extend `SqlExecutionAuditService` with `origin=SHARED_BI`, the token id, and
  viewer IP.

## 9. API surface (new)

```
# authoring (authed)
POST   /dashboards/generate            {connectionId, prompt} -> dashboardConfig (preview)
POST   /saved-dashboards               (exists) persist
POST   /dashboards/{id}/share          {expiresInDays?, password?, maxViews?, lockedParams?} -> {url, token}
GET    /dashboards/{id}/shares         list links
DELETE /dashboards/shares/{linkId}     revoke
# public (no login, whitelisted, rate-limited)
POST   /public/dashboards/{token}/unlock     -> viewer JWT
GET    /public/dashboards/{token}            -> config
POST   /public/dashboards/{token}/data       {paramValues} -> rendered data (read-only)
```

## 10. Phasing

- **P0 — Re-light (small).** Flip `SHOW_DASHBOARD_TAB`, modernize to current design,
  verify CRUD + render. **Pay down security debt: replace `{{}}` substitution with bound
  params; add the `DASHBOARD` read-only execution context (L1–L3).**
- **P1 — Agent-built dashboards (medium, the magic).** `DashboardGeneratorService` + MCP
  tools + web preview/confirm/save. *Now feasible because the brain is good.*
- **P2 — Sharing (medium-large, security-gated).** `SharedDashboardLink` + public viewer
  route + read-only public data endpoint + expiry/password/revoke + rate limiting + audit.
  Add the L4 read-only DB credential + "verify read-only" before enabling real-data shares.
- **P3 — Embedded multi-tenant + alerts.** `lockedParams` row scoping, signed-iframe embed,
  metric alerts (reuse growth/Sentinel/Slack alerting), result caching at scale.

## 11. Launch tie-in

Build the demo on the **seeded sample-DB playground** (zero risk): one-line install →
"ask DeepSQL to build a dashboard on the sample data" → share a public link. All the magic,
no real-customer-data exposure. Gate real-data external sharing behind P2's read-only-credential
+ verify step.

## 12. Open decisions for the team

1. **L4 read-only credential:** require it for any external share, or allow L1–L3-only
   shares with a warning? (Recommend: require for real data; allow L1–L3 on playground.)
2. **Viewer identity for embeds:** anonymous link vs signed-JWT embed (host app vouches for
   the viewer + passes the tenant scope). Embeds want the latter.
3. **Refresh model:** on-load live vs cached-with-TTL vs scheduled snapshot (cost vs freshness).
4. **"Data apps" scope:** explicitly out for v1 (interactivity/write-back is a much larger
   surface). Dashboards + sharing first.
