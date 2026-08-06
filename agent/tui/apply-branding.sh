#!/usr/bin/env bash
# Rebrand the TUI to "DeepSQL Agent" in an agent checkout, then rebuild the
# Node/Ink dist. Idempotent. The TUI logo/tagline live in the TUI *source*
# (not the skin), so this overlay edits them and rebuilds — vendored
# customization, applied like agent/webui/apply-overlay.sh.
#
# Handles the clean string edits (tagline, default-skin branding) + rebuild.
# The ASCII logo/hero blocks are in README.md (multi-line art doesn't sed
# cleanly) — apply those once to ui-tui/src/banner.ts, then re-run this.
#
# Usage: ./apply-branding.sh [path-to-agent]   (default: ~/.hermes/hermes-agent)
set -euo pipefail

AGENT="${1:-${HERMES_HOME:-$HOME/.hermes}/hermes-agent}"
BRAND="$AGENT/ui-tui/src/components/branding.tsx"
SKINS="$AGENT/hermes_cli/skin_engine.py"
BANNER="$AGENT/ui-tui/src/banner.ts"
[[ -f "$BRAND" && -f "$SKINS" && -f "$BANNER" ]] || { echo "Not an agent checkout: $AGENT"; exit 1; }

# 1. Tagline: Nous Research → DeepSQL DBA/BI/guardian (with humor).
if grep -q "DROP TABLE on a Friday" "$BRAND"; then
  echo "= tagline already DeepSQL"
else
  perl -0pi -e "s/const TAG_FULL = '[^']*'/const TAG_FULL = 'DBA \xc2\xb7 BI \xc2\xb7 Guardian \xe2\x80\x94 runs your queries, tunes your indexes, and won\\\\'t let you DROP TABLE on a Friday'/" "$BRAND"
  perl -0pi -e "s/const TAG_MID = '[^']*'/const TAG_MID = 'DBA \xc2\xb7 BI \xc2\xb7 Guardian of your database'/" "$BRAND"
  perl -0pi -e "s/const TAG_TINY = '[^']*'/const TAG_TINY = 'DeepSQL'/" "$BRAND"
  echo "+ tagline rebranded"
fi

# 2. Default-skin branding strings (kills any pre-skin flash + sets the
#    response label / agent name before the 'deepsql' skin loads).
if grep -q '"agent_name": "DeepSQL Agent"' "$SKINS"; then
  echo "= default-skin branding already DeepSQL"
else
  perl -0pi -e 's/"agent_name": "Hermes Agent"/"agent_name": "DeepSQL Agent"/g; s/" \xe2\x9a\x95 Hermes "/" DeepSQL "/g' "$SKINS"
  echo "+ default-skin branding rebranded"
fi

# 3. Logo/hero check (manual — see README.md for the exact art blocks).
if grep -q '\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x95\x97 \xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x95\x97\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x95\x97' "$BANNER"; then
  echo "= logo already DeepSQL wordmark"
else
  echo "! logo not yet replaced — paste the DeepSQL LOGO_ART + CADUCEUS_ART from agent/tui/README.md into ui-tui/src/banner.ts"
fi

# 4. Rebuild the TUI dist.
( cd "$AGENT/ui-tui" && npm run build >/dev/null 2>&1 ) && echo "+ TUI dist rebuilt" || echo "! TUI rebuild failed — run 'npm run build' in ui-tui/"
echo "✓ DeepSQL TUI branding applied to $AGENT"
