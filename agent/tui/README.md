# DeepSQL TUI branding (vendored overlay)

The TUI's logo, hero art, and tagline live in the TUI **source**
(`ui-tui/src/banner.ts`, `ui-tui/src/components/branding.tsx`) and its default
skin in `hermes_cli/skin_engine.py` (upstream module path) — not in a skin YAML.
So rebranding to "DeepSQL Agent" means editing those files in the agent checkout
and rebuilding the Node/Ink dist (like `agent/webui/apply-overlay.sh` for the webui).

Changing the *defaults* (not just our `deepsql` skin) also removes the brief
flash of default branding at startup — there's no default left to render before
our skin loads.

Also customized in `ui-tui/src/components/branding.tsx`:
- **Welcome panel** — the default "Available Tools / Available Skills" dump is
  replaced with a curated DeepSQL intro (DBA · BI · Product-dev with sample
  prompts + a "✨ Try" product-update highlight). See the `What I can do` block.
  The left hero/metadata column (db cylinder + model/cwd/session) is removed and
  the title is centered full-width (`wide` branch deleted; `w = cols - 12`).
- **Composer placeholders** — `ui-tui/src/content/placeholders.ts` `PLACEHOLDERS`
  swapped from coding-agent prompts ("write a test for…") to DBA/BI ones
  ("why is the orders query slow?", "what indexes should I add?", …).
- **Readability** — body palette brightened in `skins/deepsql.yaml` (`banner_dim`
  #ADADAD for prompts, `banner_text` #E8E8E8 for descriptions); the wordmark stays
  light-grey (`banner_title` #D0D0D0 / `ui_accent` #E0E0E0 / `banner_border` #808080).
- **Model line** — updated to show `· DeepSQL` (two spots). (The remaining
  vendor strings are in `billingOverlay.tsx`, which never renders for us — we
  use the backend LLM proxy.)

`apply-branding.sh` does the clean string edits (tagline + default-skin
branding) and rebuilds. The two ASCII-art blocks below must be pasted into
`ui-tui/src/banner.ts` once (multi-line box-drawing art doesn't sed cleanly).

## `LOGO_ART` (replace the default wordmark) — a clean solid-block "DEEPSQL"

Solid blocks with letter spacing (no shadow `╗╔═` strokes — those render as
visual noise and hurt legibility):

```
const LOGO_ART = [
  '████   ████   ████   ████    ████    ███    █     ',
  '█   █  █      █      █   █   █       █   █   █     ',
  '█   █  ███    ███    ████     ███    █   █   █     ',
  '█   █  █      █      █            █  █  ██   █     ',
  '████   ████   ████   █        ████    ███   █████ '
]
```

## `CADUCEUS_ART` (replace the caduceus hero) — a small database cylinder

```
const CADUCEUS_ART = [
  '   ╭───────╮   ',
  '   ╞═══════╡   ',
  '   │       │   ',
  '   ╞═══════╡   ',
  '   │       │   ',
  '   ╰───────╯   '
]
```

## Colors

The wordmark color comes from the active skin's banner palette
([agent/skins/deepsql.yaml](../skins/deepsql.yaml)) — kept in a tight grey
range so it reads as a subtle, understated grey rather than bright white.

## Productization note

These are edits to the agent clone (lost on re-pull). For the shipped `deepsql`
CLI we **prebuild and bundle** the patched TUI dist in the npm package (Phase 3),
so end users get the branded TUI with no build step.
