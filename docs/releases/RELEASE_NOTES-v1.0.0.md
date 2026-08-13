# DeepSQL v1.0.0

**First public open-source release** — self-hosted database agent for PostgreSQL and MySQL.

## Highlights

- **Ask your database** — schema exploration, SQL generation, slow-query help, and index guidance through the web UI or MCP/CLI.
- **Brain** — indexes your schemas (including non-`public` Postgres schemas) so the agent has real context.
- **DeepSQL Agent** — per-user profiles, MCP tool access scoped to your login, dashboard HTML artifacts with a read-only query bridge.
- **BYO model** — point at OpenAI, Azure OpenAI, or any OpenAI-compatible endpoint. No vendor lock-in on inference.
- **Self-host first** — credentials stay in your vault DB; Compose builds from source.

## Install

```bash
git clone https://github.com/DeepSQLAI/deepsql.git
cd deepsql
git checkout v1.0.0
cp .env.example .env
# set SECURITY_JWT_SECRET, ENCRYPTION_KEY, and DEEPSQL_CHAT_* in .env
docker compose up --build -d
```

Air-gapped / non-Compose: download the JAR + frontend tarball from this Release, verify `SHA256SUMS`, and follow `docs/oss-ux/RELEASE.md`.

## Artifacts in this release

| File | Contents |
|------|----------|
| `deepsql-1.0.0-source.tar.gz` / `.zip` | Source tree at this tag |
| `dba-agent-backend-1.0.0.jar` | Spring Boot executable |
| `deepsql-frontend-1.0.0.tar.gz` | Production static UI |
| `deepsql-mcp-0.27.0.tgz` | CLI + MCP server package |
| `sbom-*.cdx.json` | CycloneDX SBOMs |
| `SHA256SUMS` / `SHA512SUMS` | Integrity hashes |
| `manifest.json` | Machine-readable inventory |

Verify:

```bash
sha256sum -c SHA256SUMS
```

## Security notes

Critical/high items addressed before this cut are listed in `CHANGELOG.md` and `docs/oss-ux/OSS_SECURITY_REVIEW.md`. Please report new vulnerabilities per `SECURITY.md`.

## Verify before upgrading production

On a staging stack, run:

```bash
python3 scripts/self-host/e2e-agent-check.py
```

Expect `AGENT_OK True` and `DASH_OK True`.
