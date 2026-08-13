# DeepSQL OSS release — security review

**Date:** 2026-08-13  
**Scope:** Auth surfaces, secrets/defaults, injection/isolation, self-host attack surface  
**Audience:** Ship readiness alongside the usability fix track  
**Method:** Code review of `SecurityConfig`, compose, controllers, query policy, Agent/Hermes wiring (spot-verified Critical items)

**Companion:** Product fix plan → [`E2E_FIX_PROPOSAL.md`](./E2E_FIX_PROPOSAL.md)

---

## Executive verdict

**Do not market as “safe for multi-user internet exposure” until Criticals are closed.**

Auth-on-by-default, signup closed, bootstrap localhost+secret, encrypted vault, and cookie HttpOnly are a solid base. The OSS-blocking problems are:

1. **Missing connection ACL on dangerous APIs** (IDOR → kill sessions / apply indexes on another user’s DB)
2. **SQL injection in kill-session** (`pid` concatenated into SQL)
3. **Hermes `:8787` on `0.0.0.0`** bypasses nginx `auth_request`
4. **Compose publishes Postgres/Valkey/backend** with weak defaults + **unauthenticated fat Actuator**
5. **Blank JWT secret fails open** (ephemeral key)

Usability agents can keep shipping product fixes; **security Criticals should land before Sunday** (or the launch messaging must be “single-admin, localhost / private network only”).

> **Status (2026-08-13):** S1–S5 implementation landed — kill-session pid validation + prepared SQL; ACL on apply/kill/slow-log/growth/saved-query/playbook/configuration; Hermes default `127.0.0.1`; compose loopback binds + Valkey `requirepass` + Actuator lockdown; JWT fail-closed under prod/auth. See [`SECURITY.md`](../../SECURITY.md). Remaining High/Medium (H4–H9, M*) are still open.

---

## Severity legend

| Level | Meaning for OSS |
|-------|-----------------|
| **Critical** | Remote or any-auth’d-user → vault/DB damage; must fix or restrict deployment model |
| **High** | Likely exploit on typical cloud self-host; fix before public announce |
| **Medium** | Defense-in-depth / misconfig footgun; fix soon or document loudly |
| **Low** | Polish / residual |

---

## Critical (must fix or constrain launch)

### C1. Connection IDOR on dangerous endpoints

Many controllers take `{connectionId}` and never call `AccessControlService`, while Schema/Chat/Brain correctly do.

**Confirmed:** `IndexRecommendationController` has **no** `AccessControlService` / `assertCan*` usage.  
**Also reported (same pattern):** ActiveQuery kill, LockContention kill, SlowLogSource, GrowthMonitoring (webhooks), SavedQuery, Playbook, Configuration.

**Impact:** Any logged-in `DEVELOPER` who obtains a connection UUID can apply indexes / kill backends / reconfigure slow-log credentials / fire webhooks on another tenant’s connection.

**Fix:** Mandatory ACL interceptor or per-controller `assertCanReadConnection` / `assertCanManageConnectionContent`. Apply/kill = manage (or ADMIN). Integration tests: user B → 403 on user A’s id.

---

### C2. SQL injection in session kill

```193:211:backend/src/main/java/com/dbaagent/service/ActiveQueryService.java
// pid concatenated:
"SELECT pg_terminate_backend(" + pid + ")";
"KILL QUERY " + pid;
```

**Impact:** With C1, crafted `pid` runs attacker SQL on the target DB connection.

**Fix:** `pid` must match `^[0-9]+$`; bind parameters; ACL first. Same for lock-contention kill. Fix dialect string match (`postgres` vs `POSTGRESQL`) while there.

---

### C3. Hermes webui bound `0.0.0.0:8787`

```28:28:scripts/self-host/setup-agent.sh
WEBUI_HOST="${HERMES_WEBUI_HOST:-0.0.0.0}"
```

Nginx gates `/agent-api/` via `auth_request`; **direct `:8787` does not**. Vite `/agent-api` proxy also has **no** cookie gate (dev only).

**Impact:** Anyone who can reach `:8787` talks to the Agent/MCP path without DeepSQL login.

**Fix:** Default `HERMES_WEBUI_HOST=127.0.0.1`; firewall drop WAN 8787; smoke-test refuses open bind; document as hard requirement. Keep nginx `auth_request`.

---

### C4. Compose attack surface + fat Actuator

| Surface | Issue |
|---------|--------|
| Postgres `:5432` published | Default password `postgres` |
| Valkey `:6379` published | **No password** |
| Backend `:8080` published | Bypasses nginx |
| `/actuator/**` `permitAll` | `health,info,metrics,caches,prometheus` + `show-details=always` |

**Impact:** Typical “open ports on a cloud VM” install exposes vault DB, cache, metrics, and unauthenticated backend APIs’ recon surface.

**Fix:**
- Do not publish Postgres/Valkey (internal network only); or `127.0.0.1:` only.
- Generate strong `DB_PASSWORD` in `install.sh` (like JWT/encryption).
- Valkey `--requirepass` + env password.
- Backend port optional / localhost-only; public traffic via `:3000` only.
- Actuator: auth-required except `health`; `show-details=when-authorized`; drop prometheus from public exposure.

---

### C5. Blank `SECURITY_JWT_SECRET` → ephemeral key

`JwtUtil` warns and generates a random key instead of refusing to start under `prod`.

**Impact:** “Works” insecurely; multi-replica broken; easy to ship without a real secret.

**Fix:** Fail closed when `SPRING_PROFILES_ACTIVE=prod` (or always when auth enabled) if secret missing/short (<32 bytes).

---

## High

| ID | Finding | Fix |
|----|---------|-----|
| **H1** | `SECURITY_AUTH_ENABLED=false` → every request is synthetic ADMIN | Fail boot under prod if false; scrub stale “auth disabled” docs |
| **H2** | CSRF disabled + cookie session | Keep SameSite=Lax; for public HTTPS prefer CSRF token; never `SameSite=None` without CSRF |
| **H3** | Cookie `Secure` defaults **false** | prod: set `SECURITY_COOKIE_SECURE=true` when `APP_PUBLIC_URL` is https |
| **H4** | Read-only policy allows arbitrary `SET` / `USE` preambles | Allowlist safe SETs; block `SET ROLE`, `SESSION AUTHORIZATION`, dangerous `search_path` |
| **H5** | SSRF: webhooks, ES hosts, LLM endpoint test, optional `verifySsl=false` | Deny link-local/metadata/loopback; HTTPS + domain allowlist |
| **H6** | Public dashboard share = unauth read-only SQL on connection | Default require share password; rotate token on revoke; rate-limit |
| **H7** | MCP tokens = full-user PATs (no scopes) | Document; revoke on logout/staff exit; roadmap connection-scoped tokens |
| **H8** | `/auth/internal/token` mint-admin if `INTERNAL_TEST_TOKEN` set | Keep out of `.env.example`; refuse under prod profile |
| **H9** | Index apply DDL outside query policy + no ACL | Same as C1 + confirm + manage ACL |

---

## Medium

| ID | Finding | Fix |
|----|---------|-----|
| **M1** | `server.error.include-message=always` | `never` / `on-param` in prod |
| **M2** | `spring.jpa.show-sql=true` in prod | Off |
| **M3** | `ENCRYPTION_KEYS` parse errors may echo key material | Redact in exceptions/logs |
| **M4** | SSH `StrictHostKeyChecking=no` | Configurable; default warn/strict for new hosts |
| **M5** | `@CrossOrigin("*")` on some controllers | Remove; rely on global CORS allowlist |
| **M6** | `/admin/bootstrap/link` localhost-only but no secret | Require same bootstrap secret as `/users/admin/bootstrap` |
| **M7** | No gitleaks / secret scanning in CI | Add gitleaks or GitHub secret scanning |
| **M8** | `curl \| bash` Docker/Hermes install | Pin checksums or document trust boundary |
| **M9** | Stale docs: admin/admin, auth-off defaults | Fix before OSS to avoid operator footguns |
| **M10** | Demo seed weak passwords | OK if opt-in + loud “not for production” |
| **M11** | RBAC: VIEWER collapses to DEVELOPER | True read-only role or document “two roles only” |
| **M12** | Vite `/agent-api` ungated | Document “dev only”; never expose Vite publicly |

---

## What’s already in good shape

- Auth **on** by default; prod profile hardcodes true; signup / setup initialize closed
- Vault credentials encrypted (AES-GCM); encryption key required at startup
- LLM keys encrypted at rest; masked on read
- Chat/MCP/dashboard query paths use `READ_ONLY_ONLY`
- Public share tokens: 192-bit SecureRandom
- Dashboard artifacts: sandboxed iframe (`allow-scripts` only) + strict CSP
- Code archive extract: zip-slip + size caps
- Hermes host toolsets (shell/browser/computer_use) disabled by install scripts
- No committed live API keys in tracked git; `.env` gitignored
- No privileged containers / docker.sock
- CORS defaults localhost-only (not `*`)
- Cookies HttpOnly + SameSite=Lax
- Dependabot + CodeQL present

---

## OSS launch postures (pick one)

### A. “Private / single-admin self-host” (viable this weekend if Criticals slip)

Announce as:
- Single trusted admin (or fully trusted team)
- **Not** multi-tenant SaaS
- Bind to private network / Tailscale / SSH tunnel
- Do not publish 5432/6379/8080/8787

Still fix **C2** (kill SQLi) and **C1** for apply/kill at minimum — those are bugs, not “deployment choices.”

### B. “Internet-facing multi-user” (needs Critical + High)

Require C1–C5, H1–H6, compose bind hardening, Actuator lockdown, Hermes localhost, JWT fail-closed, share-password default.

---

## Recommended fix order (security track)

```text
S1  Kill-session pid validation + prepared SQL                    (C2)   — hours
S2  ACL on apply / kill / slow-log / growth / saved-query / …   (C1)   — 1–2 days
S3  Hermes default 127.0.0.1 + smoke assert                      (C3)   — hours
S4  Compose: no public DB/Redis; generate DB_PASSWORD; Redis auth (C4) — hours
S5  Actuator lockdown + JWT fail-closed under prod               (C4/C5)
S6  SET preamble allowlist under READ_ONLY                       (H4)
S7  SSRF guards for webhooks / LLM / ES                          (H5)
S8  Share password default + token rotate on revoke              (H6)
S9  Cookie Secure when HTTPS; prod error/sql logging             (H3/M1/M2)
S10 Docs scrub + SECURITY.md + gitleaks                          (M7/M9)
```

Can run **in parallel** with the usability PR train (W1–W7). Do not block Brain/Agent UX PRs on S6–S10; **do** gate merge of “ready for OSS” on S1–S5.

---

## SECURITY.md outline (ship with release)

1. Threat model: trusted admins vs untrusted multi-tenant (be honest).
2. Required network layout diagram (only `:3000` public).
3. Secrets checklist: JWT, ENCRYPTION_KEY, DB_PASSWORD, Valkey, LLM, AGENT_PROVISION_SECRET.
4. Post-install: disable bootstrap flag; rotate bootstrap secret.
5. MCP tokens = full account access.
6. Public dashboard links = credentials.
7. Reporting channel for vulnerabilities.
8. Explicit non-goals until ACL complete.

---

## Quick post-install smoke (add to `smoke-test.sh`)

```bash
# Expect 401/404 without cookies:
curl -sf -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/actuator/prometheus   # not 200 if locked
curl -sf http://127.0.0.1:8080/api/auth/me                                                  # 401
curl -sf http://127.0.0.1:3000/agent-api/                                                   # 401 via nginx
# Expect connection refused / filtered from non-loopback perspective in hardened install:
# :5432 :6379 :8787 :8080 not on 0.0.0.0
ss -lntp | grep -E ':(5432|6379|8787|8080)\b'
```

---

## Bottom line

| Question | Answer |
|----------|--------|
| Secrets leaked in git? | **No** live keys found in tracked files |
| Safe default auth model? | **Mostly yes** (auth on, signup closed) |
| Safe multi-user on a public VM? | **Not yet** — IDOR + kill SQLi + open Hermes/DB/Redis/Actuator |
| Sunday possible? | **Yes as private single-admin** if S1–S2 land; **internet multi-user needs S1–S5 + H4–H6** |
