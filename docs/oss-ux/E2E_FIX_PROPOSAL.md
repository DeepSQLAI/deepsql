# DeepSQL OSS launch — end-to-end fix proposal

**Date:** 2026-08-13  
**Scope:** Close every gap from the usability critique so the default path is:

> install → login → (wizard if needed) → Brain indexes the real schema → Agent answers

**Success metric for Sunday:** A fresh Docker install + `seed-demo-data.sh`, and a multi-schema Postgres DB, both complete “ask about largest tables / row counts” without auth or “Brain ready but empty” failures.

**Companion:** Security track is in [`OSS_SECURITY_REVIEW.md`](./OSS_SECURITY_REVIEW.md) (run in parallel; gate “internet multi-user” on S1–S5).

---

## Guiding principles

1. **One source of truth for “ready.”** Connection green + Brain complete + Agent boot OK must mean the same tables the user expects are queryable by the Agent.
2. **Fail loud, early.** Never burn 6–11 tool steps before saying “MCP token expired.”
3. **Default path is narrow.** Index → optional notes → ask Agent. Advanced jobs stay behind a fold.
4. **Ship in thin vertical slices** that each leave main greener than before.

---

## Workstream map

| ID | Workstream | Severity | Est. size | Depends on |
|----|------------|----------|-----------|------------|
| **W1** | Agent ↔ DeepSQL MCP auth reliability | Blocker | L | — |
| **W2** | Multi-schema Postgres Brain + coverage gate | Blocker | L | — |
| **W3** | Revive first-run onboarding / setup gate | Blocker | M | W2 for BrainInit step quality |
| **W4** | Brain UI honesty (stages, stepper, jobs) | Blocker | M | W2 for coverage fields |
| **W5** | Unresolved false positives (PK joins) | High | M | — |
| **W6** | Polish (suggestions, title, dbType, dedupe) | Medium | S | — |
| **W7** | Docs + smoke gates for launch | High | S | W1–W4 |

Parallelize **W1 ∥ W2 ∥ W5 ∥ W6**; then **W3/W4** on top of W2 APIs; finish with **W7**.

---

## W1 — Agent ↔ DeepSQL MCP auth (blocker)

### Root cause

Three credential stores diverge:

| Store | Used by |
|-------|---------|
| Browser session cookie / JWT | UI connection health |
| `~/.config/deepsql/auth.json` | CLI |
| Hermes profile `DEEPSQL_AUTH_TOKEN` (env snapshot in long-lived MCP child) | Agent tools |

UI “ADMIN” only proves (1). Agent failures are (3) stale/revoked + MCP process not live-reloading env. Logout revokes DB token but leaves disk plaintext. Provisioner often skipped on native runs (`deepsql-agent:8788` DNS). Circuit breaker then reports “unreachable.”

### Fix design

**Goal:** Every Agent session starts with a freshly minted (or extended) MCP token that the *running* MCP process can see, and the UI surfaces health before chat.

#### 1. Token file + live re-read (already half-built in MCP)

- Provisioner writes `~/.hermes/profiles/u-<user>/deepsql.token` (0600).
- Set `DEEPSQL_TOKEN_FILE` in `mcp_servers.deepsql.env` (keep `DEEPSQL_AUTH_TOKEN` as fallback).
- MCP lib already re-reads file + self-heals on 401 — **wire it in provisioners**.

**Files:**  
`scripts/local-agent-provisioner.py`, Compose agent provision path, `scripts/self-host/setup-agent.sh`, `mcp/deepsql-phase1-lib.js` (verify/tests).

#### 2. Provision must not be best-effort for Agent tab

- `AgentBridgeService.ensureProfile` / `callProvisioner`: if provisioner configured, **fail the session bootstrap** on non-2xx (don’t silently return profile name with stale disk token).
- Native AGENTS.md / start scripts: require `AGENT_PROVISION_SECRET` + local provisioner when `SECURITY_AUTH_ENABLED=true`.

**Files:**  
`AgentBridgeService.java`, `AgentBridgeController.java`, `scripts/start-backend.sh` (or docs only if script already supports it).

#### 3. Revoke → disk cleanup

- On `revokeAgentTokens`, clear profile token file / blank `DEEPSQL_AUTH_TOKEN` via provisioner hook (or best-effort file delete under known profile path).

**Files:**  
`AuthController.java`, `AgentBridgeService.java`, provisioner.

#### 4. Agent boot health check (UX)

- Extend `POST /api/agent/session` (or add `GET /api/agent/health`) to: mint/extend → provision → **probe** `GET /api/connections` with the just-written token.
- `AgentChatPanel` boot: if probe fails, show blocking banner: “Agent cannot reach DeepSQL (auth). Reconnect / check Agent runtime.” Disable send. No chat until green.

**Files:**  
`AgentBridgeController.java`, `src/lib/api/agentClient.js`, `AgentChatPanel.jsx`.

#### 5. Launch smoke

- Extend `scripts/self-host/e2e-agent-check.py` (or twin) to fail CI/smoke if Agent tool path 401s.
- Document: plain `deepsql` CLI ≠ Hermes MCP auth.

### Acceptance

- [ ] Fresh install: Agent answers “how many customers?” on `demo_shop` without manual token copy.
- [ ] Logout → login: Agent still works (re-mint + token file).
- [ ] Rotating token on disk without Hermes restart: next tool call succeeds (token file).
- [ ] Failed provision: UI shows explicit error before first user message.

---

## W2 — Multi-schema Postgres Brain + coverage gate (blocker)

### Root cause

`PostgresIntrospectionProvider` hardcodes `schemaname = 'public'` (and siblings). Brain init progress is **stage-bucket %**, not schema coverage — so 2 public views → COMPLETED 100% while 18 business tables exist. CLI SQL bypasses introspection, so the product feels schizophrenic.

### Fix design

#### 1. Default scan = all non-system schemas

Replace `= 'public'` with:

```sql
schemaname NOT IN ('pg_catalog','information_schema','pg_toast')
AND schemaname NOT LIKE 'pg_temp_%'
AND schemaname NOT LIKE 'pg_toast_temp_%'
```

Always set `TableMetadata.schema` from catalog; use map keys `schema.name` to avoid collisions.

**Epicenter:** `PostgresIntrospectionProvider.java` (`scanSchema`, `getTablesAndViews`, `getAllTablesWithMetadata`, column/index/FK batches).

**Also align:** `QueryExecutorService.getPostgreSQLObjects`, `SchemaIntrospectionService`, privilege checks, chat “list tables” prompts that still say public-only, advisor/stats paths that copy the filter. Copy the exclusion pattern already used in Brain classification services.

#### 2. Optional schema allow-list on connection

- Add `includedSchemas` (nullable JSON list; null/empty = all non-system).
- Wizard: after successful test, multi-select schemas (`pg_namespace`).
- Brain UI: edit allow-list → Re-initialize.
- Update privilege docs/snippets beyond `GRANT … ON SCHEMA public`.

**Files:**  
`DatabaseConnection` / `ConnectionRequest`, migrations or ddl-auto field, ConnectionWizard, `PrivilegesAccordion.js`, introspection filter `ANY(?)`.

#### 3. Coverage gate before COMPLETED

After `SCHEMA_SCAN`:

- `liveUserTableCount` = countable non-system (or allow-listed) base tables visible to the JDBC user.
- `tablesDiscovered` = what we indexed.
- If `liveUserTableCount > 0 && tablesDiscovered == 0` → **FAILED** with clear message.
- If coverage < threshold (e.g. < 80% of live base tables) → do **not** `markCompleted(100)`; stay in a `NEEDS_ATTENTION` / incomplete state with `coveragePercent`, `schemasScanned`, `skippedSchemas`.
- Init-status API exposes these fields for UI.

**Files:**  
`BrainInitStageExecutor.java`, init-status DTOs, `BackgroundJobsTab.jsx` / Brain enrichment card.

#### 4. Privileges UX

After multi-schema scan, surface missing `USAGE`/`SELECT` as Brain warnings (not silent empty). Update wizard grant template to selected schemas.

### Acceptance

- [ ] `acme_erp`-shaped DB: Brain lists `crm.*` / `sales.*` / … (or explicit allow-list), not only `pg_stat_statements`.
- [ ] Zero user tables indexed ⇒ cannot show “Complete 100%.”
- [ ] `demo_shop` (public only) still greens.
- [ ] Duplicate table names in two schemas don’t overwrite each other in snapshots.

---

## W3 — First-run onboarding (blocker)

### Root cause

`Onboarding.jsx` is complete product work but **unrouted**. Bootstrap/`install.sh` may set `setup.complete=true` early, so that flag ≠ “ready to use.” Login always goes to dashboard. Payload field names in the wizard don’t match `ConnectionRequest`.

### Fix design

#### 1. Semantics

| Concept | Signal |
|---------|--------|
| Admin exists | user table / bootstrap done |
| Product ready | `hasConnections && hasLlmConfig` (and optionally Brain init done for first connection) |
| Wizard complete | `setup.wizard.complete` **or** derive from above — stop using bootstrap’s `setup.complete` alone |

#### 2. Wire routes + gate

- `App.jsx`: `/onboarding` protected; `/setup` → `/onboarding`.
- After login (`useAuth.jsx`): if `!hasConnections` → `/onboarding` (primary). Soft-nudge if `!hasLlmConfig`.
- `Login.jsx`: when status shows no connections / incomplete wizard, CTA “Finish setup” → `/onboarding` (and link to README for install-script admins).
- Fix wizard save fields: `connectionName`, `dbType: postgres`, `database` (not `name`/`databaseType`/`databaseName`).

#### 3. Empty states

- Sidebar / Home: if zero connections, unlock Docs + onboarding CTA; keep other nav locked with “Add a database.”
- Optional: post-login modal “Load demo_shop” calling seed guidance or deep-link to docs/script output.

### Acceptance

- [ ] Fresh admin with no connections lands in wizard, not a locked dashboard.
- [ ] Completing wizard creates connection, configures LLM, kicks Brain init, marks product ready.
- [ ] `/onboarding` no longer blank.

---

## W4 — Brain UI honesty (blocker)

### Root causes

1. Frontend `STAGE_ORDER` ≠ backend `InitStage` → grey stages at 100%.
2. Stepper defaults to Initialize / background-jobs and never auto-advances on COMPLETED.
3. Ten scheduled jobs dominate the “teach your business” home.

### Fix design

#### 1. Sync stage enum

Make `BackgroundJobsTab.jsx` `STAGE_ORDER` / labels match `InitStage.java` exactly (`DATA_SAMPLING`, `COLUMN_VALUE_COLLECTION`, `INFERRED_RELATIONSHIPS`, `RAG_EMBEDDING`, `BRAIN_ANALYSIS`, `SEMANTIC_MODELING`, …). On `COMPLETED`, mark all pipeline stages done.

#### 2. Stepper behavior

- When init `COMPLETED` and user hasn’t chosen a tab → default to **Add context / schema-context**, not jobs.
- Step 1 shows checkmark when complete; only the active step is highlighted.
- Show coverage line from W2: “Indexed 18/18 tables across 5 schemas” or “Indexed 0/18 — fix grants or schema list.”

#### 3. Jobs demotion

- Brain home: enrichment card + next-step CTA only.
- “Scheduled maintenance (N)” collapsed by default; expand for power users.
- Prefer connection-scoped jobs in the summary; globals behind toggle.

### Acceptance

- [ ] Complete init ⇒ all listed stages green; stepper on “Add context.”
- [ ] First paint after ready is not a wall of 10 job cards.
- [ ] Incomplete coverage from W2 is visible on the enrichment card.

---

## W5 — Unresolved false positives (high)

### Root cause

`KeyColumnAnalysisService.detectAntiPatterns` emits `UNINDEXED_JOIN` when `joinCount >= 5 && indexName == null`, but `enrichWithIndexStats` is a **stub** — never sets `indexName`. Normal PK joins become Unresolved ANTI_PATTERN noise.

### Fix design

1. Implement index enrichment via dialect introspection (PK/UK/indexes → `indexName`).
2. Skip `UNINDEXED_*` when column is PK/UK / `TRUE_KEY`.
3. Ambiguity panel: only HIGH actionable types; allow dismiss/acknowledge to stick.
4. Retune copy: “Possible missing index” ≠ “Brain can’t disambiguate.”

**Files:** `KeyColumnAnalysisService.java`, `SchemaAmbiguityService.java`, `UnresolvedPanel.jsx`.

### Acceptance

- [ ] `demo_shop` customers.id / orders.customer_id do **not** appear as Unresolved solely for being join keys with a PK/index.
- [ ] Genuine unindexed join columns still surface.

---

## W6 — Polish (medium)

| Item | Fix | Files |
|------|-----|-------|
| Hardcoded “bookings” suggestions | Schema-aware chips from top tables / generic fallbacks | `AgentChatPanel.jsx` |
| Title “DBA Agent” | `<title>DeepSQL</title>` (+ optional login badge copy) | `index.html`, `Login.jsx` |
| POSTGRES vs POSTGRESQL | Canonicalize to `postgres` on write via `DatabaseProviderRegistry.getCanonicalName`; display “PostgreSQL” | `CredentialService.java`, `src/lib/dbType.js`, connection UIs |
| Duplicate connections | Dedupe or 409 on same owner+host+port+database; wizard warn | `CredentialService` / `ConnectionController` |
| Login tone | Soften “work email” for OSS or dual copy | `Login.jsx` |

### Acceptance

- [ ] Suggestions never mention tables absent from the active connection.
- [ ] Browser tab says DeepSQL.
- [ ] New connection with same host/db doesn’t silently double.

---

## W7 — Docs + launch gates (high)

1. **In-app Docs:** short “Web UI first hour” — add connection → Brain → Agent (link CLI docs second).
2. **README:** call out multi-schema + Agent health; “Brain ready means indexed coverage.”
3. **Smoke matrix** (must pass on release branch):

| Check | Command / assertion |
|-------|---------------------|
| Install + login | `install.sh` / existing smoke |
| demo_shop Agent Q&A | e2e-agent-check customer count |
| Multi-schema fixture | mini `crm`+`sales` DB; Brain `tablesDiscovered >= N`; Agent lists those tables |
| Coverage gate | public-only views fixture must **not** report Complete 100% if user tables exist elsewhere |
| Onboarding route | `/onboarding` renders stepper |
| Stage UI | COMPLETED ⇒ no grey pipeline stages |

4. **Release checklist** in PR template / DocsSection.

---

## Suggested implementation order (PRs)

```text
PR1  W2a — Postgres non-system schema scan + qualified names          (backend)
PR2  W1  — Token file + provision fail-loud + Agent boot probe        (backend + scripts + Agent UI)
PR3  W2b — Coverage gate + init-status fields                         (backend)
PR4  W4  — Stage enum sync + stepper + collapse jobs + coverage UI    (frontend)
PR5  W5  — Index enrichment + Unresolved filter                       (backend + small UI)
PR6  W3  — Onboarding route + auth gate + payload fix + login CTA     (frontend + setup semantics)
PR7  W2c — Schema allow-list on connection + wizard                   (full-stack)
PR8  W6  — Polish                                                     (frontend + normalize)
PR9  W7  — Docs + smoke fixtures                                      (scripts + docs)
```

**Minimum viable Sunday cut** if time-boxed: **PR1 + PR2 + PR3 + PR4 + PR5 + thin PR6 (route + redirect only) + agent/demo smoke**. Defer allow-list UI (PR7) if default non-system scan is enough; keep privilege messaging in Brain errors.

---

## Combined go-live checklist (UX + security)

### Must ship (product)

- [ ] W1 Agent MCP auth reliable + boot banner
- [ ] W2a/b Multi-schema scan + coverage gate
- [ ] W4 Brain stages/stepper/jobs honesty
- [ ] W5 Unresolved PK noise fixed
- [ ] W3 thin: `/onboarding` routed + redirect when no connections

### Must ship (security) — see `OSS_SECURITY_REVIEW.md`

- [ ] S1 Kill-session SQLi
- [ ] S2 ACL on apply/kill (and ideally slow-log/growth)
- [ ] S3 Hermes bind `127.0.0.1`
- [ ] S4 Compose: no public DB/Redis; strong DB password; Redis auth
- [ ] S5 Actuator lockdown + JWT fail-closed under prod

### Launch posture

| Posture | Extra requirements |
|---------|-------------------|
| **Private single-admin** | Network: only `:3000` public; messaging honest |
| **Internet multi-user** | All Criticals + H4 SET allowlist, H5 SSRF, H6 share password |

---

## Risk notes

| Risk | Mitigation |
|------|------------|
| Multi-schema scan slows Brain init / embedding cost | Cap concurrency; allow-list; skip system-like schemas later |
| Larger snapshots / RAG volume | Monitor; document; optional “index views” toggle default off for `pg_stat_*` |
| Provisioner required in all envs | Fail closed in UI; make `AGENT_PROVISION_SECRET` mandatory in `.env.example` |
| Breaking clients that assumed public-only | Changelog; allow-list for lock-down |

---

## Out of scope (explicit)

- Renaming Brain → something else (IA is fine once jobs are demoted).
- Re-enabling `AGENTS_ENABLED` scheduled-agent product.
- Full Flyway adoption.
- Desktop Electron polish.

---

## One-sentence launch bar

**Ship when: green connection + Brain complete ⇒ Agent can name and query the user’s real tables (including non-`public` schemas) without a human pasting MCP tokens — and dangerous APIs enforce connection ACL.**
