# DeepSQL v1.2.0

**Weekly product cut** — schema-scoped access policies, admin profile switch, and multi-schema hardening on top of `v1.1.0`.

## Highlights

- **Schema-scoped chat access policies** — plain-English rules resolve to allowed schemas plus table/column deny lists; schema APIs and query guardrails enforce them per user.
- **Admin profile switch** — “View as” another user to validate policies without sharing credentials (`/admin/impersonate`).
- **ACME ERP fixture** — six-schema Postgres database (`crm`, `sales`, `finance`, `inventory`, `hr`, `marts`) for policy and Brain tests; seed with `scripts/seed-acme-erp.sh`.
- **Postgres introspection fix** — `getTableColumns` respects the caller’s schema (#60).
- **Editor + security fixes** — SQL guard bypasses closed (#63); `COMMENT`/`CALL` no longer misclassified as mutations (#64).
- **Dashboard UX** — loading states (#61); Hermes agent/web UI pinned to release tags (#68).

## Install / upgrade

```bash
git clone https://github.com/DeepSQLAI/deepsql.git
cd deepsql
git checkout v1.2.0
cp .env.example .env
# set SECURITY_JWT_SECRET, ENCRYPTION_KEY (or ENCRYPTION_KEYS), and DEEPSQL_CHAT_* in .env
docker compose up --build -d
```

From `v1.1.0`: pull/checkout `v1.2.0` and rebuild Compose (or swap JAR + frontend tarball from this Release). Schema is managed by `ddl-auto=update`; no new hand SQL is required for this cut.

Air-gapped: download artifacts below, verify `SHA256SUMS`, follow `docs/oss-ux/RELEASE.md`.

## Artifacts in this release

| File | Contents |
|------|----------|
| `deepsql-1.2.0-source.tar.gz` / `.zip` | Source tree at this tag |
| `dba-agent-backend-1.2.0.jar` | Spring Boot executable |
| `deepsql-frontend-1.2.0.tar.gz` | Production static UI |
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
python3 scripts/self-host/e2e-multischema-check.py
```

Expect `AGENT_OK True`, `DASH_OK True`, and `✓ Multi-schema E2E OK`.

## Cadence

Product releases are cut **weekly, Saturday 09:00 America/Los_Angeles**. See `docs/oss-ux/RELEASE.md` and `docs/oss-ux/WEEKLY_RELEASE_AUTOMATION.md`.
