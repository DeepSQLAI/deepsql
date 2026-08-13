# E2E release verification — 2026-08-13

Stack: native Cloud VM (Postgres 16 + Redis + Spring Boot `:8080` + Vite `:3000` + Hermes `:8787` + provisioner `:8788`). `main` at `7833f31` (post security PR #49). Redeployed backend from packaged `dba-agent-backend-1.0.0.jar`.

## Results

| Check | Result | Notes |
|-------|--------|-------|
| `GET /api/actuator/health` | PASS | `200` |
| Anonymous Prometheus | PASS | `401` (not exposed) |
| Login `admin@localhost` | PASS | `auth_token` cookie |
| Connections list | PASS | 3 connections incl. `demo_shop` |
| Brain init status | PASS | `COMPLETED` / 100% |
| `/onboarding` | PASS | `200`, title DeepSQL |
| Agent session `mcpAuthOk` | PASS | `true` after provisioner restart |
| Agent Q&A (`e2e-agent-check.py`) | PASS | `execute_sql` → `demo_shop` |
| Dashboard generate | PASS | HTML + `deepsql.query` |
| Kill malicious PID | PASS | `400` Invalid session id |
| Logout | PASS | subsequent `/auth/me` → `401` |
| MCP JS syntax | PASS | `node --check` clean |
| Hermes bind | PASS | `127.0.0.1:8787` |

## Ops notes discovered during redeploy

1. **Provisioner must run current tree** — an Aug-12 `local-agent-provisioner.py` process lacked `DEEPSQL_TOKEN_FILE` writes; restarting from `scripts/local-agent-provisioner.py` fixed profile token files.
2. **Hermes webui caches MCP env** — after rotating tokens / updating `~/.hermes/config.yaml`, restart `hermes-webui` or Agent tab MCP calls 401 with stale env (USER_ID=`deepsql-agent`, no `DEEPSQL_AUTH_TOKEN`). Dashboard generate stayed green because it uses the channel-token path.
3. Syncing default `~/.hermes/config.yaml` `mcp_servers.deepsql.env` from the active `u-admin` profile after provision keeps native/dev Agent tab healthy.

## Release gate

Functional smoke + agent/dashboard E2E are green for the `v1.0.0` artifact cut. Remaining security Highs in `OSS_SECURITY_REVIEW.md` are documented, not blockers for this tagged OSS release.
