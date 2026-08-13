#!/usr/bin/env bash
# Build industry-standard GitHub release artifacts for DeepSQL.
#
# Produces under release-artifacts/<tag>/:
#   deepsql-<ver>-source.tar.gz / .zip   — reproducible source archives (git archive)
#   dba-agent-backend-<ver>.jar         — Spring Boot executable JAR
#   deepsql-frontend-<ver>.tar.gz       — production Vite build
#   deepsql-mcp-<mcp-ver>.tgz           — npm pack of @deepsql/mcp
#   sbom-frontend.cdx.json              — CycloneDX SBOM (npm), when tooling available
#   sbom-backend.cdx.json               — CycloneDX SBOM (Maven), when tooling available
#   SHA256SUMS / SHA512SUMS             — checksums for every artifact above
#   RELEASE_NOTES.md / manifest.json
#
# Usage (from repo root):
#   ./scripts/release/build-artifacts.sh [v1.0.0]
#
# Environment:
#   SKIP_FRONTEND=1   skip npm ci / production build
#   SKIP_BACKEND=1    skip mvn package (reuse existing JAR)
#   SKIP_SBOM=1       skip CycloneDX generation
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

VERSION_RAW="${1:-}"
if [[ -z "$VERSION_RAW" ]]; then
  VERSION_RAW="$(git describe --tags --exact-match 2>/dev/null || true)"
fi
if [[ -z "$VERSION_RAW" ]]; then
  VERSION_RAW="v$(grep -m1 '<version>' backend/pom.xml | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')"
fi
VERSION="${VERSION_RAW#v}"
TAG="v${VERSION}"

MCP_VERSION="$(python3 -c "import json; print(json.load(open('mcp/package.json'))['version'])")"
# Must NOT live under Vite's outDir (`dist/`): `vite build` empties that tree and
# would delete source archives / JAR mid-run if we staged here.
OUT="$ROOT/release-artifacts/${TAG}"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "==> DeepSQL release artifacts for ${TAG}"
echo "    output: ${OUT}"

# ── Source archives (git tree at HEAD; excludes untracked / ignored) ──────────
echo "==> Source archives"
PREFIX="deepsql-${VERSION}"
git archive --format=tar.gz --prefix="${PREFIX}/" HEAD > "${OUT}/${PREFIX}-source.tar.gz"
git archive --format=zip --prefix="${PREFIX}/" HEAD > "${OUT}/${PREFIX}-source.zip"

# ── Backend JAR ───────────────────────────────────────────────────────────────
if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
  echo "==> Backend package (skip tests)"
  (cd backend && ./mvnw -B -ntp -DskipTests package)
fi
JAR_SRC="backend/target/dba-agent-backend-${VERSION}.jar"
if [[ ! -f "$JAR_SRC" ]]; then
  JAR_SRC="$(ls -1 backend/target/dba-agent-backend-*.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "${JAR_SRC}" || ! -f "$JAR_SRC" ]]; then
  echo "ERROR: backend JAR not found under backend/target/" >&2
  exit 1
fi
cp -f "$JAR_SRC" "${OUT}/$(basename "$JAR_SRC")"

# ── Frontend production build ─────────────────────────────────────────────────
if [[ "${SKIP_FRONTEND:-0}" != "1" ]]; then
  echo "==> Frontend production build"
  if [[ ! -d node_modules ]]; then
    npm ci
  fi
  npm run build:production
fi

FRONTEND_DIST="$ROOT/dist"
if [[ ! -f "$FRONTEND_DIST/index.html" ]]; then
  echo "ERROR: frontend build output missing (expected ${FRONTEND_DIST}/index.html)" >&2
  exit 1
fi
tar -C "$FRONTEND_DIST" -czf "${OUT}/deepsql-frontend-${VERSION}.tar.gz" .

# ── MCP npm pack ──────────────────────────────────────────────────────────────
echo "==> MCP package"
(
  cd mcp
  if [[ ! -d node_modules ]]; then
    npm ci --omit=dev 2>/dev/null || npm install --omit=dev
  fi
  npm pack --pack-destination "$OUT"
)
MCP_PACK="$(ls -1 "${OUT}"/@deepsql-mcp-*.tgz 2>/dev/null | head -1 || true)"
if [[ -n "$MCP_PACK" ]]; then
  mv -f "$MCP_PACK" "${OUT}/deepsql-mcp-${MCP_VERSION}.tgz"
fi

# ── SBOMs (best-effort; never fail the release build) ─────────────────────────
if [[ "${SKIP_SBOM:-0}" != "1" ]]; then
  echo "==> SBOMs (CycloneDX)"
  if command -v npx >/dev/null 2>&1; then
    npx --yes @cyclonedx/cyclonedx-npm@3.1.0 \
      --output-file "${OUT}/sbom-frontend.cdx.json" \
      --output-reproducible \
      --ignore-npm-errors \
      || echo "WARN: frontend SBOM generation skipped"
  fi
  if [[ -x backend/mvnw ]]; then
    if (cd backend && ./mvnw -B -ntp -DskipTests \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
      -DoutputFormat=json \
      -DoutputName=bom \
      -DincludeDevelopmentScope=false); then
      cp -f backend/target/bom.json "${OUT}/sbom-backend.cdx.json"
    else
      echo "WARN: backend SBOM generation skipped"
    fi
  fi
fi

# ── Release notes ─────────────────────────────────────────────────────────────
for candidate in \
  "docs/releases/RELEASE_NOTES-${TAG}.md" \
  "docs/releases/RELEASE_NOTES-v${VERSION}.md" \
  "CHANGELOG.md"
do
  if [[ -f "$candidate" ]]; then
    cp -f "$candidate" "${OUT}/RELEASE_NOTES.md"
    break
  fi
done

# ── Checksums + manifest ──────────────────────────────────────────────────────
echo "==> Checksums + manifest"
export RELEASE_OUT="$OUT" RELEASE_VERSION="$VERSION" RELEASE_TAG="$TAG" RELEASE_MCP="$MCP_VERSION"
export RELEASE_COMMIT="$(git rev-parse HEAD)"
export RELEASE_DESCRIBE="$(git describe --always --dirty 2>/dev/null || true)"
python3 <<'PY'
import hashlib, json, os, time
from pathlib import Path

out = Path(os.environ["RELEASE_OUT"])
files = sorted(
    p for p in out.iterdir()
    if p.is_file() and not p.name.startswith(".") and not p.name.startswith("SHA")
)
lines256, lines512 = [], []
artifacts = []
for p in files:
    data = p.read_bytes()
    h256 = hashlib.sha256(data).hexdigest()
    h512 = hashlib.sha512(data).hexdigest()
    lines256.append(f"{h256}  {p.name}")
    lines512.append(f"{h512}  {p.name}")
    artifacts.append({"name": p.name, "bytes": len(data), "sha256": h256})

(out / "SHA256SUMS").write_text("\n".join(lines256) + ("\n" if lines256 else ""))
(out / "SHA512SUMS").write_text("\n".join(lines512) + ("\n" if lines512 else ""))

# Re-hash including checksum files themselves for the manifest listing
all_files = sorted(p for p in out.iterdir() if p.is_file() and not p.name.startswith("."))
manifest_artifacts = []
for p in all_files:
    data = p.read_bytes()
    manifest_artifacts.append({
        "name": p.name,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    })

manifest = {
    "product": "DeepSQL",
    "version": os.environ["RELEASE_VERSION"],
    "tag": os.environ["RELEASE_TAG"],
    "mcpVersion": os.environ["RELEASE_MCP"],
    "gitCommit": os.environ["RELEASE_COMMIT"],
    "gitDescribe": os.environ["RELEASE_DESCRIBE"],
    "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "artifacts": manifest_artifacts,
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
PY

echo ""
echo "✓ Artifacts ready in ${OUT}"
ls -lh "$OUT"
