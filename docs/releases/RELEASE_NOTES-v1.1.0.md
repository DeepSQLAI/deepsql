# DeepSQL v1.1.0

**Weekly product cut** — dashboards, multi-schema UX, and Performance consolidation on top of `v1.0.0`.

## Highlights

- **Progressive dashboards** — builds stream a shell, then widgets; clone, folders/favorites, version history, refresh/TV kiosk, and AI-evaluated alerts.
- **Server-owned dashboard chat** — generation turns persist even if the SSE client disconnects; concurrent turns are rejected safely.
- **Multi-schema UI** — Editor, Brain, and Advisor surfaces treat non-`public` Postgres schemas as first-class.
- **Performance hub** — Slow Queries and Workload Analysis live in one Performance area.
- **CI unblock** — CodeQL visibility guard that blocked merges is removed.

## Install / upgrade

```bash
git clone https://github.com/DeepSQLAI/deepsql.git
cd deepsql
git checkout v1.1.0
cp .env.example .env
# set SECURITY_JWT_SECRET, ENCRYPTION_KEY (or ENCRYPTION_KEYS), and DEEPSQL_CHAT_* in .env
docker compose up --build -d
```

From `v1.0.0`: pull/checkout `v1.1.0`, rebuild Compose (or swap JAR + frontend tarball from this Release), and apply hand SQL for dashboard tables if you are not relying on `ddl-auto=update`:

- `V111` / `V112` — `generation_status`, optimistic `version` on `saved_dashboards`
- `V113` — `dashboard_versions`
- `V114` — `dashboard_alerts`

(No Flyway runtime — see `CLAUDE.md`.)

Air-gapped: download artifacts below, verify `SHA256SUMS`, follow `docs/oss-ux/RELEASE.md`.

## Artifacts in this release

| File | Contents |
|------|----------|
| `deepsql-1.1.0-source.tar.gz` / `.zip` | Source tree at this tag |
| `dba-agent-backend-1.1.0.jar` | Spring Boot executable |
| `deepsql-frontend-1.1.0.tar.gz` | Production static UI |
| `deepsql-mcp-0.27.0.tgz` | CLI + MCP server package (unchanged npm line) |
| `sbom-*.cdx.json` | CycloneDX SBOMs |
| `SHA256SUMS` / `SHA512SUMS` | Integrity hashes |
| `manifest.json` | Machine-readable inventory |

```bash
sha256sum -c SHA256SUMS
```

## Verify

```bash
python3 scripts/self-host/e2e-agent-check.py
```

Expect `AGENT_OK True` and `DASH_OK True`.

## Cadence

Product releases are cut **weekly, Saturday 09:00 America/Los_Angeles**. See `docs/oss-ux/RELEASE.md` and `docs/oss-ux/WEEKLY_RELEASE_AUTOMATION.md`.
