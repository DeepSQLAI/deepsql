# Security Policy

DeepSQL stores database credentials in an encrypted vault, holds an AES-GCM key
whose loss is unrecoverable, and enforces read-only SQL execution as a guardrail.
We treat reports against those paths as our highest priority.

## Reporting a vulnerability

Report privately via **Security → Report a vulnerability** on this repository.
Do not open a public issue, and do not describe the problem in a pull request.

We acknowledge reports within **48 hours**, provide an assessment within
**5 business days**, and aim to ship a fix within **90 days**. We credit
reporters in the published advisory unless you prefer otherwise.

If a report is time-critical and you have had no acknowledgement within 48 hours,
open a public issue containing no technical detail — just a request that a
maintainer check private reports — and we will pick it up.

## Supported versions

The latest tagged release receives security fixes. Older tags do not.

## In scope

- Credential-vault encryption and key handling
- Read-only SQL execution enforcement, and any bypass of it
- Authentication, JWT handling, and MCP token authorisation
- The admin bootstrap endpoint
- The dashboard sandbox iframe and its read-only query bridge, including the
  public share path
- SSH tunnelling
- Connection access control (IDOR) on connection-scoped APIs
- Reachable dependency vulnerabilities

## Out of scope

These are by design, and reporting them will get a courteous decline:

- Behaviour when `SECURITY_AUTH_ENABLED=false`. This is a development-only
  shortcut and is documented as such.
- The hand-written SQL editor's ability to mutate data for a confirming admin.
  A DBA tool that cannot run `UPDATE` is not a DBA tool; the guardrail governs
  *generated* and *agent-issued* SQL, not a human who has explicitly confirmed.
- The localhost-only bootstrap endpoint when deliberately enabled.
- Anything requiring prior host compromise.
- Missing hardening headers with no demonstrated impact.

## How fixes are handled

Fixes are developed in a private fork through GitHub Security Advisories. The
advisory and the patched release are published simultaneously. A vulnerability is
never fixed in a normal public pull request: on a repository anyone can watch,
that commit is a roadmap to the bug for everyone still running the old version.

## A note on review requirements

`.github/CODEOWNERS` routes changes under the vault, authentication and
SQL-execution paths to the security owners, so the right people are *required*
reviewers. It cannot, however, require a larger *number* of approvals on those
paths specifically — GitHub carries a single repo-wide approval count. The
two-approval rule on security-critical paths is therefore a maintainer
convention, enforced by reviewers rather than by the platform. Treat a
security-path pull request carrying only one approval as not yet ready.

## Please do not

- Test against infrastructure you do not own.
- Include real credentials, API keys, or `ENCRYPTION_KEY` values in a report.
  Redact them; we can reproduce from a description.

---

## Threat model (operators)

| Posture | Who is trusted | Network |
|---------|----------------|---------|
| **Private / single-admin** | One admin (or a fully trusted ops team) | Private network, Tailscale, or SSH tunnel. Prefer only `:3000` reachable from clients. |
| **Internet multi-user** | Untrusted tenants sharing one install | Requires ACL on every connection-scoped API, hardened compose binds, Actuator lockdown, Hermes behind nginx only, JWT fail-closed — see [`docs/oss-ux/OSS_SECURITY_REVIEW.md`](docs/oss-ux/OSS_SECURITY_REVIEW.md). |

DeepSQL is **not** marketed as multi-tenant SaaS until remaining High findings (SSRF, SET allowlist, share-password defaults, residual controller ACL) from that review are addressed. Criticals S1–S5 (kill SQLi, connection ACL on dangerous APIs, Hermes/compose loopback, Valkey auth, Actuator lockdown, JWT fail-closed) are closed in current `main`.

## Required network layout

```text
Internet / LAN clients
        │
        ▼
   :3000 frontend (nginx)
        ├── /api/*        → backend:8080   (auth cookies / JWT)
        └── /agent-api/*  → deepsql-agent:8787  (auth_request → /api/auth/me)

Host loopback only (not WAN):
   127.0.0.1:5432  postgres
   127.0.0.1:6379  valkey (--requirepass)
   127.0.0.1:8080  backend (debug / health probes)
   127.0.0.1:8787  agent API
   127.0.0.1:8788  agent provisioner
```

Do **not** publish Postgres, Valkey, backend, or Hermes on `0.0.0.0` on a cloud VM.

## Secrets checklist

| Secret | Purpose |
|--------|---------|
| `SECURITY_JWT_SECRET` | Session token signing (≥32 bytes). **Required** under `prod` / auth-on — boot fails closed if missing. |
| `ENCRYPTION_KEY` / `ENCRYPTION_KEYS` | Vault credential encryption |
| `DB_PASSWORD` | Vault Postgres (never leave as `postgres` on a networked host) |
| `DEEPSQL_VALKEY_PASSWORD` | Valkey `--requirepass` (required by Compose) |
| `DEEPSQL_CHAT_*` / embedding keys | LLM |
| `AGENT_PROVISION_SECRET` | Backend ↔ agent provisioner |

`./scripts/self-host/install.sh` generates JWT, encryption, DB, Valkey, bootstrap, and provision secrets when placeholders remain.

## Post-install

1. Disable admin bootstrap (`SECURITY_ADMIN_BOOTSTRAP_ENABLED=false`) after the first admin exists.
2. Rotate `ADMIN_BOOTSTRAP_SECRET` if it was ever logged or shared.
3. Confirm `SPRING_PROFILES_ACTIVE=prod` and `SECURITY_AUTH_ENABLED` is not forced off.

## MCP tokens

MCP tokens are **full-account PATs** (same authority as the minting user). Revoke on logout / staff exit. Prefer short-lived tokens; connection-scoped tokens are a roadmap item.

## Public dashboard links

A public share token authorizes **read-only SQL** against the dashboard’s connection while `is_public` is true. Treat share URLs like credentials; revoke by deleting the share / flipping `is_public`. Prefer password-protected shares once that control ships.

## Explicit non-goals (until complete)

- Guaranteeing every legacy controller has connection ACL (track remaining High/Medium in the security review)
- Scoping MCP tokens to a single connection
- Hardening every SSRF-capable webhook / LLM endpoint tester
