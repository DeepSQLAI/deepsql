#!/usr/bin/env bash
# Idempotently apply the DBA skin to an agent webui checkout.
# Source of truth is dba-skin.css in this dir; this script is the "overlay"
# so the clone's files can be regenerated after a re-pull.
#
# Usage: ./apply-overlay.sh [path-to-webui]   (default: ~/.hermes/hermes-webui)
# Default path is the upstream Hermes webui install location (HERMES_HOME contract).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBUI="${1:-${HOME}/.hermes/hermes-webui}"
CSS="$WEBUI/static/style.css"
BOOT="$WEBUI/static/boot.js"
MARKER="DeepSQL DBA — Agent WebUI skin"

[[ -f "$CSS" && -f "$BOOT" ]] || { echo "Not a webui checkout: $WEBUI"; exit 1; }

# 1. Append the skin CSS once (guard on the marker comment).
if grep -qF "$MARKER" "$CSS"; then
  echo "= skin CSS already present in style.css"
else
  printf '\n' >> "$CSS"; cat "$HERE/dba-skin.css" >> "$CSS"
  echo "+ appended dba-skin.css to style.css"
fi

# 2. Register the skin in boot.js _SKINS once.
if grep -qF "value:'dba'" "$BOOT"; then
  echo "= 'dba' already registered in boot.js _SKINS"
else
  perl -0pi -e "s/(const _SKINS=\[\n)/\$1  {name:'DBA', value:'dba', colors:['#111111','#FFFFFF','#444444']},\n/" "$BOOT"
  grep -qF "value:'dba'" "$BOOT" && echo "+ registered 'dba' in boot.js _SKINS" || { echo "! failed to register skin in boot.js"; exit 1; }
fi

# 3. Rebrand the product name: "Hermes" → "DeepSQL Agent" (UI display strings only;
#    leaves functional identifiers like the `hermes` CLI / hermes-agent paths alone).
BRAND="DeepSQL Agent"
STATIC="$WEBUI/static"
if grep -qF ">$BRAND<" "$STATIC/index.html" 2>/dev/null; then
  echo "= already rebranded to '$BRAND'"
else
  # Default assistant-name fallbacks in JS (boot.js: s.bot_name||'Hermes', ui.js: _botName||'Hermes')
  perl -0pi -e "s/\|\|'Hermes'/||'$BRAND'/g" "$STATIC/boot.js" "$STATIC/ui.js" 2>/dev/null || true
  # Visible product strings
  perl -0pi -e "s/Hermes WebUI/$BRAND/g" "$STATIC"/*.js "$STATIC"/*.html "$STATIC"/sw.js 2>/dev/null || true
  perl -0pi -e "s{<title>Hermes</title>}{<title>$BRAND</title>}g; s{(app-titlebar-title[^>]*>)Hermes(<)}{\${1}$BRAND\${2}}g; s/Message Hermes/Message $BRAND/g; s/(content=\")Hermes(\")/\${1}$BRAND\${2}/g" "$STATIC/index.html" 2>/dev/null || true
  echo "+ rebranded product name to '$BRAND'"
fi

# 3b. Register 'dba' in the webui's Python-side skin allow-list (api/config.py),
#     else the backend resets an unknown skin back to 'default' on save/load.
if grep -qF '"dba",' "$WEBUI/api/config.py" 2>/dev/null; then
  echo "= 'dba' already in Python _SETTINGS_SKIN_VALUES"
else
  perl -0pi -e 's/(_SETTINGS_SKIN_VALUES = \{\n)(\s*)"default",/$1$2"default",\n$2"dba",/' "$WEBUI/api/config.py" \
    && grep -qF '"dba",' "$WEBUI/api/config.py" && echo "+ registered 'dba' in Python skin allow-list" || echo "! could not register 'dba' server-side"
fi

# 4. Default theme + skin (so a fresh load ships the DBA look, no manual pick).
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
python3 - "$HERMES_HOME/webui/settings.json" <<'PY' || echo "! could not set default theme/skin (set in Settings → Appearance)"
import json, sys, pathlib
p = pathlib.Path(sys.argv[1]); p.parent.mkdir(parents=True, exist_ok=True)
d = json.loads(p.read_text()) if p.exists() else {}
d["theme"], d["skin"] = "light", "dba"
p.write_text(json.dumps(d, indent=2))
print("+ default theme=light skin=dba")
PY

# 5. Logo: replace the default logo with a neutral DeepSQL database mark (currentColor → tracks skin).
if grep -qF 'aria-label="DeepSQL Agent"' "$STATIC/index.html" 2>/dev/null; then
  echo "= logo already swapped"
else
  perl -0pi -e 's{<div class="empty-logo"><svg.*?</svg></div>}{<div class="empty-logo"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="72" height="72" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-label="DeepSQL Agent"><ellipse cx="32" cy="15" rx="18" ry="7"/><path d="M14 15 v34 c0 3.9 8.1 7 18 7 s18-3.1 18-7 V15"/><path d="M14 32 c0 3.9 8.1 7 18 7 s18-3.1 18-7"/></svg></div>}s' "$STATIC/index.html" \
    && grep -qF 'aria-label="DeepSQL Agent"' "$STATIC/index.html" && echo "+ swapped logo to DeepSQL database mark" || echo "! logo swap skipped"
fi

echo "✓ Overlay applied to $WEBUI"
echo "  Default theme/skin set. (Hard-refresh an open tab to clear cached assets.)"
