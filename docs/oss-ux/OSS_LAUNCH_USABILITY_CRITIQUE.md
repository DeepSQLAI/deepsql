# DeepSQL OSS launch usability critique

**Date:** 2026-08-12  
**Build:** `main` @ `3ed7d82` (at time of review)  
**Tested as:** `admin@localhost` on Vite `localhost:3000` + backend `:8080`  
**Focus:** first-run configure/run simplicity, especially Brain

**Follow-ups:**  
- Fix proposal → [`E2E_FIX_PROPOSAL.md`](./E2E_FIX_PROPOSAL.md)  
- Security review → [`OSS_SECURITY_REVIEW.md`](./OSS_SECURITY_REVIEW.md)

---

## Verdict

**Not Sunday-ready for a smooth OSS “clone → run → ask your DB” experience.**

The README/Docker self-host path is thoughtfully written, and the **Brain surface on a simple `public`-schema demo (`demo_shop`) is the best part of the product** — clear left-to-right pipeline, good table cards, human review gate. But the core loop an OSS user expects — **connect DB → Brain indexes it → Agent answers** — fails or misleads in ways that will generate Day-1 GitHub issues:

1. **Agent chat fails** with “unauthorized/unreachable” while the UI shows a healthy `ADMIN` connection.
2. **Multi-schema Postgres Brain is effectively blind** (indexes only `public`, then claims “Complete 100%”).
3. **First-run web wizard is dead code** (`Onboarding.jsx` exists; `/onboarding` is blank; `/setup` redirects to dashboard).
4. Several Brain empty/complete states are **internally inconsistent**, so users cannot trust the green bar.

Docker `install.sh` may paper over (1) for a fresh install; (2)–(4) are product issues that will hit real databases immediately.

---

## What worked

| Area | Notes |
|------|--------|
| Login UX | Clean, fast, clear value prop. |
| Nav IA | Agent → Dashboards → Digest → Brain → … is discoverable. |
| Brain pipeline (demo) | “Initialize → Add context → Review → Knowledge base” is the right mental model. |
| Brain on `demo_shop` | 3/3 tables, FACT/DIMENSION/LOOKUP labels, AI blurbs — feels teachable. |
| Connections modal | Usable; Add Connection CTA is obvious. |
| Docs section | Strong CLI/MCP install story. |
| README Quick start | Honest about Docker/buildx, BYO model, bootstrap secrets. |
| CLI | `deepsql whoami` / `connections list` / SQL on `acme_erp` worked. |

---

## Launch blockers (fix before OSS announce)

### B1. Agent says “unauthorized/unreachable” while UI says connection is fine

**Repro:** Login → Agent → ask “How many customers…?” on `demo_shop` or `acme_erp`.  
**Result:** 6–11 tool steps, then failure.  
**Sidebar still shows** `demo_shop ADMIN`.

This is the **hero feature** of the homepage (“Ask about your database”). Shipping with a green connection badge and a broken Agent loop will dominate launch feedback.

**UX ask:** Before/while chatting, surface Agent↔DeepSQL auth health explicitly. Do not burn 11 opaque steps first.

### B2. Brain “Complete” on multi-schema DBs that were barely indexed

**`acme_erp` reality:** 18 business tables across `crm` / `sales` / `finance` / `inventory` / `hr`.  
**Brain/schema API reality:** 2 objects in `public` — `pg_stat_statements` (+ info view).  
**Init:** ~1.2s, progress **100%**, copy **“All set! Brain is ready.”**

CLI can `SELECT` the real schemas; Brain schema scan does not (`PostgresIntrospectionProvider` hardcodes `public`).

**UX ask:** Never show 100% Complete if indexed table count ≪ live table count. Fail loud.

### B3. Web first-run wizard is not reachable

- `src/pages/Onboarding.jsx` exists but `App.jsx` has **no `/onboarding` route**; `/setup` → `/dashboard`.
- Login has **no** first-install CTA (signup is localhost bootstrap fallback only).

### B4. Misleading Brain stage / job UI

- Frontend stage keys ≠ backend `InitStage` → grey stages at 100%.
- Initialize stays highlighted after complete.
- Ten jargon-heavy scheduled jobs dominate day-one Brain home.

---

## High-priority polish

| Issue | Why it hurts |
|------|----------------|
| Hardcoded Agent suggestions (“bookings”) | Wrong for demo/acme; first click feels broken |
| Unresolved ANTI-PATTERN on normal PK joins | Index enrichment stub → false positives |
| Duplicate `demo_shop` + `POSTGRES` vs `POSTGRESQL` | Looks sloppy |
| Document title “DBA Agent” | Branding drift |
| Docs CLI/MCP-first | Missing web “add DB → Brain → Agent” path |

---

## Hello-world results (review session)

| Action | Result |
|--------|--------|
| Login | Success |
| Brain on `demo_shop` | Success — 3 tables indexed |
| Brain on `acme_erp` | Misleading success — only `pg_stat_statements*` |
| Agent Q&A | **Fail** — unauthorized/unreachable MCP |
| CLI query on `acme_erp` | Success — sees `crm`/`sales`/… |

---

## Launch bar

**“Brain ready” must mean the Agent can see the same tables the user expects — and the Agent must actually be authenticated when the connection pill is green.**
