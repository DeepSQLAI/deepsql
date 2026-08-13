# DeepSQL Security

## Threat model (be honest)

| Posture | Who is trusted | Network |
|---------|----------------|---------|
| **Private / single-admin** | One admin (or a fully trusted ops team) | Private network, Tailscale, or SSH tunnel. Prefer only `:3000` reachable from clients. |
| **Internet multi-user** | Untrusted tenants sharing one install | Requires ACL on every connection-scoped API, hardened compose binds, Actuator lockdown, Hermes behind nginx only, JWT fail-closed — see [`docs/oss-ux/OSS_SECURITY_REVIEW.md`](docs/oss-ux/OSS_SECURITY_REVIEW.md). |

DeepSQL is **not** marketed as multi-tenant SaaS until the Criticals in that review are closed and High findings (SSRF, SET allowlist, share-password defaults) are addressed.

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
| `DEEPSQL_VALKEY_PASSWORD` | Valkey `--requirepass` |
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

## Reporting vulnerabilities

Please report security issues privately to the maintainers (GitHub Security Advisory on [DeepSQLAI/deepsql](https://github.com/DeepSQLAI/deepsql) preferred). Do not open public issues that include exploit details until a fix is available.

## Explicit non-goals (until complete)

- Guaranteeing every legacy controller has connection ACL (track remaining High/Medium in the security review)
- Scoping MCP tokens to a single connection
- Hardening every SSRF-capable webhook / LLM endpoint tester
