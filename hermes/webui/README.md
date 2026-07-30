# DeepSQL Agent WebUI — DBA skin (Phase 2)

Source of truth for branding the agent webui (the web chat surface that replaces the React `AgentView`) to the DBA Agent design.

- `dba-skin.css` — a `data-skin="dba"` skin: minimal black/white/grey, Inter-first, flat surfaces, light + dark variants. Modeled on the built-in `graphite` skin.

## Install into a webui checkout

The webui is a separate cloned repo (default `~/.hermes/hermes-webui`). Apply the skin with the idempotent overlay script (safe to re-run, e.g. after re-pulling):

```bash
hermes/webui/apply-overlay.sh [path-to-webui]   # default: ~/.hermes/hermes-webui
```

It appends `dba-skin.css` to `static/style.css` and registers the skin in `static/boot.js` `_SKINS`, each guarded so re-runs are no-ops. Then reload the UI and select it: `/theme dba` then `/theme light` (or Settings → Appearance).

## Notes / follow-ups (Phase 2/3)

- **Vendoring:** these are edits to a cloned repo and will be lost on re-pull. Productization should maintain this as a patch/overlay applied at deploy (or fork the webui) so the repo stays source of truth. Pin matching webui ↔ agent release trains (the webui couples to the agent by direct import).
- **Inter font:** the skin prefers `Inter` then falls back to system sans. For guaranteed Inter, bundle the webfont locally (don't depend on Google Fonts in a self-hosted deploy).
- **DBA result rendering:** richer rendering of SQL result tables / EXPLAIN plans is a separate enhancement on top of the skin.
- **Approval UX:** read-only `deepsql:*` tools currently prompt for approval each call (smart mode). For a DBA chat, auto-approve the read-only DeepSQL tool surface (they're server-side read-only enforced) so turns flow without per-tool clicks.
