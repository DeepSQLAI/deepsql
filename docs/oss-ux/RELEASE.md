# DeepSQL release process

How we cut an official GitHub release with builds, source archives, checksums, and SBOMs.

## Versioning

| Surface | Where | Current |
|---------|-------|---------|
| Product / Git tag | `vMAJOR.MINOR.PATCH` | `v1.0.0` |
| Backend JAR | `backend/pom.xml` `<version>` | `1.0.0` |
| Frontend package | root `package.json` | `0.1.0` (internal) |
| MCP / CLI npm | `mcp/package.json` | `0.27.0` |

Tag the **product** version (`v1.0.0`). Keep backend `pom.xml` in lockstep with the tag for the JAR filename. MCP may continue its own semver when publishing `@deepsql/mcp` to npm.

## Pre-flight (release manager)

1. `main` is green on CI (`frontend`, `backend`, `mcp`, `compose-build`).
2. Smoke matrix from `docs/oss-ux/E2E_FIX_PROPOSAL.md` W7 passes on a redeployed stack:
   - login / auth cookie
   - Brain init `COMPLETED`
   - Agent Q&A (`scripts/self-host/e2e-agent-check.py`) → `AGENT_OK` + `DASH_OK`
   - `/onboarding` renders
   - security smoke: anonymous Prometheus `401`, malicious kill pid `400`
3. `CHANGELOG.md` and `docs/releases/RELEASE_NOTES-vX.Y.Z.md` updated.

## Local artifact build

```bash
./scripts/release/build-artifacts.sh v1.0.0
# → release-artifacts/v1.0.0/
```

Skip expensive rebuilds when iterating:

```bash
SKIP_BACKEND=1 SKIP_FRONTEND=1 ./scripts/release/build-artifacts.sh v1.0.0
```

Verify checksums:

```bash
cd release-artifacts/v1.0.0
sha256sum -c SHA256SUMS
```

## Publish on GitHub

Preferred path (CI):

```bash
git checkout main && git pull
git tag -a v1.0.0 -m "DeepSQL v1.0.0"
git push origin v1.0.0
```

Pushing the tag runs `.github/workflows/release.yml`, which:

1. Builds source archives, backend JAR, frontend tarball, MCP pack
2. Generates CycloneDX SBOMs (best-effort)
3. Writes `SHA256SUMS` / `SHA512SUMS` + `manifest.json`
4. Creates a GitHub Release (non-draft) attaching every file under `release-artifacts/v1.0.0/`

Manual fallback (if Actions cannot publish):

```bash
./scripts/release/build-artifacts.sh v1.0.0
gh release create v1.0.0 \
  --title "DeepSQL v1.0.0" \
  --notes-file docs/releases/RELEASE_NOTES-v1.0.0.md \
  release-artifacts/v1.0.0/*
```

## Artifact set (what “industry standard” means here)

| Artifact | Purpose |
|----------|---------|
| `*-source.tar.gz` / `.zip` | Bit-for-bit source corresponding to the tag (`git archive`) |
| `dba-agent-backend-*.jar` | Runnable Spring Boot binary |
| `deepsql-frontend-*.tar.gz` | Static SPA to serve behind nginx |
| `deepsql-mcp-*.tgz` | CLI / MCP installable package |
| `sbom-*.cdx.json` | CycloneDX software bill of materials |
| `SHA256SUMS` / `SHA512SUMS` | Integrity verification |
| `manifest.json` | Machine-readable inventory (commit, sizes, hashes) |
| `RELEASE_NOTES.md` | Human-facing release notes |

Docker Compose remains the primary install path (`docker compose up --build`); the JAR + frontend tarball support air-gapped / non-Compose operators.

## Post-release

- Confirm the Release page lists every file and checksums verify.
- Announce with the tag URL + one-line upgrade note.
- Bump versions on `main` for the next cycle only after the tag is cut (avoid tagging a commit whose pom still says the previous version).
