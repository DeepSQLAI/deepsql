# DeepSQL v1.3.0

**Weekly product cut** — DeepSQL Desktop (IDE) first ship, plus Agent/Brain/Editor hardening on top of `v1.2.0`.

## Highlights

### DeepSQL Desktop (headline)

Native Electron client for a self-hosted DeepSQL server — the biggest addition in this cut (#73).

- **Thin client, not a second frontend** — embeds the live DeepSQL UI from your VM origin (no bundled SPA, no version skew).
- **Two transports** — direct TLS (system / pinned / custom CA / TOFU) or in-process SSH tunnel (`ssh2`, no `ssh` binary).
- **Connection profiles** — per-profile sessions; secrets stored as OS keychain ciphertext when available.
- **CORS requirement** — SSH tunnels use `http://127.0.0.1:<sticky-port>`, so the VM must allow loopback wildcards in `CORS_ALLOWED_ORIGINS` (`http://127.0.0.1:*,http://localhost:*`). Documented in `desktop/README.md`, `README.md`, and `docs/root/SELF_HOST_GUIDE.md`.
- **Installers** — tag `desktop-v1.0.0` after this product cut to run `.github/workflows/desktop-release.yml` (macOS / Windows / Linux native runners).

```bash
cd desktop
npm install
npm start          # or: npm run dev
# headless Linux GUI: xvfb-run npm start
```

### Agent & security

- **MCP credential isolation** — Agent provisioner no longer mirrors tokens across every Hermes profile (cross-user last-writer-wins leak closed) (#78).
- **MCP token identity binding** — server refuses a token whose owner does not match `X-DeepSQL-Client-Agent` when that header names a DeepSQL user.
- **View as Agent** — impersonated Agent sessions enforce the *target* user’s chat/data policy (#71).
- **Schema allowlist** — chat policy schema allowlist walks the whole statement, not only outer `FROM`/`JOIN` (#70).
- **Brain endpoint authz** — Brain APIs require connection content access, not merely authentication (#72).

### Brain & Review

- **Review queue approvals** — `CODE_DERIVED` CHECK compatibility, stale-list refresh, bulk decide surfaces `failures[]` (#74).
- **Suggestion approval unwedge** — schema-doc dedupe / upsert path so approvals stick; knowledge counts refresh live (#77).
- **Enforceable Agent writes** — non-blocking save bubbles; Agent only offers brain-note saves when the user can manage content (#75).

### Editor

- **CSV export bound** — export no longer re-runs unbounded / 10-minute queries that outrun the proxy (#76).
- Concurrent-run guards and cancel auditing for Editor query sessions (#76).

## Install / upgrade

```bash
git clone https://github.com/DeepSQLAI/deepsql.git
cd deepsql
git checkout v1.3.0
cp .env.example .env
# set SECURITY_JWT_SECRET, ENCRYPTION_KEY (or ENCRYPTION_KEYS), and DEEPSQL_CHAT_* in .env
# for Desktop tunnels, include loopback wildcards in CORS_ALLOWED_ORIGINS
docker compose up --build -d
```

From `v1.2.0`: pull/checkout `v1.3.0` and rebuild Compose (or swap JAR + frontend tarball from this Release). Schema is managed by `ddl-auto=update`. Hand SQL changelog `V116` (schema documentation dedupe) is optional when not relying on `ddl-auto=update` / startup initializers.

Air-gapped: download artifacts below, verify `SHA256SUMS`, follow `docs/oss-ux/RELEASE.md`.

### Optional: DeepSQL Desktop

See [`desktop/README.md`](../../desktop/README.md). After the product tag is published, cut Desktop installers with:

```bash
git tag -a desktop-v1.0.0 -m "DeepSQL Desktop v1.0.0"
git push origin desktop-v1.0.0
```

## Artifacts in this release

| File | Contents |
|------|----------|
| `deepsql-1.3.0-source.tar.gz` / `.zip` | Source tree at this tag |
| `dba-agent-backend-1.3.0.jar` | Spring Boot executable |
| `deepsql-frontend-1.3.0.tar.gz` | Production static UI |
| `deepsql-mcp-0.27.1.tgz` | CLI + MCP server package |
| `sbom-*.cdx.json` | CycloneDX SBOMs |
| `SHA256SUMS` / `SHA512SUMS` | Integrity hashes |
| `manifest.json` | Machine-readable inventory |

Desktop installers (dmg / zip / exe / AppImage / deb) attach to the separate `desktop-v1.0.0` GitHub Release when that tag is pushed.

```bash
sha256sum -c SHA256SUMS
```

## Verify

```bash
python3 scripts/self-host/e2e-agent-check.py
python3 scripts/self-host/e2e-multischema-check.py
# Desktop (optional):
cd desktop && npm run selftest:tunnel && npm run smoke -- --url https://your-deepsql-origin
```

Expect `AGENT_OK True`, `DASH_OK True`, and `✓ Multi-schema E2E OK`.

## Not in this cut

- **Workspaces & custom roles** (#80) — still open; merge after the favorite-endpoint authorization fix and rebase onto this release.

## Cadence

Product releases are cut **weekly, Saturday 09:00 America/Los_Angeles**. See `docs/oss-ux/RELEASE.md` and `docs/oss-ux/WEEKLY_RELEASE_AUTOMATION.md`.
