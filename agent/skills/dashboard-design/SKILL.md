---
name: dashboard-design
description: Design and code a self-contained HTML dashboard for DeepSQL — ground on the schema, verify SQL, then emit it as a shell plus one verified widget block at a time so the canvas builds progressively.
version: 3.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [dashboard, bi, kpi, chart, html, date-range, deepsql, database]
    related_skills: [bi-query, schema-exploration]
---

# Dashboard Design

Use when asked to build or edit a dashboard (the task says "build a self-contained HTML dashboard"). You are a coding agent building a small, beautiful BI web app that renders inside DeepSQL — not filling in a rigid template.

## The runtime you build against

Your output is a **shell block, then one widget block per KPI/chart** — not a single HTML
document. The parent assembles them (each widget's markup+script drops into its own
`[data-widget=id]` slot in the shell) and renders each widget into the live canvas the moment
its block closes, well before your whole turn finishes — so the user watches the dashboard
build piece by piece instead of staring at a blank screen for the whole generation. Both kinds
of block run inside the SAME sandboxed iframe with a bridge already injected:

```js
deepsql.connectionId                      // this connection's id (string)
await deepsql.query("SELECT ...")         // -> { columns: string[], rows: any[][] }  (read-only, access-scoped)
deepsql.ready(fn)                          // runs fn() once the bridge is live (use this to kick off loading)

// Charts — ALWAYS use these instead of hand-writing SVG. Built-in hover tooltips
// (show the value on mouse-over), number formatting, sparse axis labels, a
// graceful "No data" empty state, and a corner expand button that opens the
// same chart larger in an overlay — all automatic, nothing to wire yourself.
// Pass the deepsql.query result straight in, or [{label,value}] / [[label,value]].
// First column = label, second = value (or opts.labelKey/valueKey).
// opts: { valueFormat(fn), height, color, emptyText, title }.
deepsql.charts.bar(elOrSelector, data, opts)     // rankings, counts by day
deepsql.charts.line(elOrSelector, data, opts)     // trends over time (area+line)
deepsql.charts.donut(elOrSelector, data, opts)    // share/composition (with legend + %)
deepsql.charts.format(n)                           // human number formatter
```

**Chart sizing and the expand control are handled by the runtime — do not build your own.**
Height is fixed regardless of container width (a chart in a wide card never balloons), and every
chart already gets a corner "expand" button that opens a larger re-render in an overlay — this is
exactly the kind of per-chart chrome that's tempting to hand-roll and easy to get inconsistent
across widgets, so it's built into `deepsql.charts.*` once instead. Pass `opts.title` (the chart's
plain-business-language heading) so the expanded overlay has something to show as its title — do
not add your own zoom/expand/fullscreen button, modal, or lightbox; one already exists per chart.

Hard rules:
- Inline everything — one `<style>` in the shell, one `<script>` per widget block. **No external URLs, CDNs, fonts, or images** (blocked by CSP) and **no `fetch()`/XHR/WebSocket** — data comes only from `deepsql.query`.
- Never hardcode result data. Query live on load, and re-query when a control changes.
- There is **no placeholder convention**. You write normal SQL strings in JS and pass the finished string to `deepsql.query`. Build date filters yourself (see below).
- A widget's `<script>` touches ONLY elements inside its own block (namespace ids with the widget id, e.g. `revenue-total-val`) — never reach into another widget's slot or assume load order between widgets.

## Procedure

1. **Ground.** `get_brain_context`, `get_schema`, `list_business_rules`, `get_relationships`. Obey business rules about which table/column/filter/currency a concept uses — quote them; don't guess a similar-looking table.
2. **Design.** Decide the KPIs, charts, tables, and controls (date range, dropdowns) the request calls for. Give each one a short, stable, kebab-case widget id (e.g. `revenue-trend`) — you'll use the same id in the shell's slot and that widget's own block. Sketch the SQL for each — **schema-qualified** (`crm.orders`, not bare `orders` when the DB has multiple schemas), table-qualified columns, read-only.
3. **Handle dates correctly.** Check the column's type in the schema. If it's a real DATE/DATETIME, filter with `BETWEEN '2026-07-01' AND '2026-07-08'`. **If it's a Unix-epoch integer** (seconds), filter on the epoch: `col >= UNIX_TIMESTAMP('2026-07-01 00:00:00') AND col < UNIX_TIMESTAMP('2026-07-09 00:00:00')`. Build these strings in JS from the picker's values.
4. **Verify each widget's query BEFORE emitting that widget's block.** Run it with `execute_sql` and READ the rows: date windows bounded and inside range (never the future), KPI value types right (name = text, money = currency), totals plausible vs a `COUNT(*)`. Fix and re-run until correct — only then emit that widget.
5. **Intent checklist.** Before emitting the shell, list every explicit ask (each chart, each metric, each control like "a date range picker defaulting to today") and confirm your planned widgets cover ALL of them.
6. **Emit progressively** (see "The runtime you build against" for the exact block shapes):
   - One `dashboard-shell` block first — page chrome plus an empty, named `[data-widget=id]` slot per widget. No query logic here.
   - Then one `dashboard-widget id="..."` block per widget, each only after step 4 has verified it — its own markup AND the `<script>` that queries and renders into its own slot. Use `deepsql.charts.*` for every chart.
7. **Self-review before your final message ends** — reread everything you emitted as if you were the user opening it, and fix anything that fails this checklist:
   - Every widget has a real data source and a query you actually verified; no placeholder/lorem values.
   - Every chart uses `deepsql.charts.*` (so it has hover tooltips) and passes the correct label/value columns — no chart left blank because the data shape didn't match.
   - No `undefined` / `null` / `NaN` can reach the screen — every injected value is guarded with a fallback. Pay special attention to KPI sub-labels and any computed % (e.g. a "top source share" caption).
   - Every explicit user ask from the intent checklist is present and wired (controls default correctly and re-query on change).
   - Every widget id referenced in the shell has a matching `dashboard-widget` block, and vice versa.
   - No table/column/SQL/connection-id text is visible anywhere.
   - No AI-slop pattern from the section below is present (gradient background/hero, emoji-as-icon,
     decorative blobs/glassmorphism, uniform shadows, off-scale spacing/type, more than one accented
     "hero" card, hand-rolled chart colors or expand/zoom controls).
   If self-review finds a problem in a widget you already emitted, emit a CORRECTED `dashboard-widget`
   block with the SAME id — it replaces what was shown before. A dashboard that renders with a blank
   chart or an "undefined" label is a failed build — catch it here, don't leave it for the user to find.

## NEVER expose internals (security + UX — non-negotiable)

The dashboard is for a business end user, not a DBA. Nothing technical may appear anywhere the user can see:

- **No table names, column names, or SQL** in titles, labels, descriptions, captions, or tooltips. Not `CUSTOMERS.subscription_start_date`, not `CUSTOMER_ORDERS.booking_made_on`, not `DATE_SUB(CURDATE(), INTERVAL 30 DAY)`, not `WHERE ... IS NOT NULL`.
- **No connection id / UUID**, no "read-only", no "grounded on", no "cohort source", no schema/database jargon.
- The header description explains what the dashboard shows in **plain business language** ("Daily new properties and booking volume, and your busiest customers"), never how it's computed.
- KPI labels are business terms ("New properties", "Bookings", "Avg bookings/day"), with an optional short plain-English sub-line ("in the selected period") — never a column reference.

Keep all the schema/SQL reasoning to yourself; the user sees only clean business metrics.

## Design system (default look)

A base theme (Maven Pro font + a neutral black/white/grey palette) is **already injected** — do NOT import fonts or set `font-family`, and build on these CSS variables rather than inventing colors:

- `--ds-bg` page, `--ds-surface` cards, `--ds-surface-2` subtle fills, `--ds-line` borders
- `--ds-ink` primary text, `--ds-ink-2` secondary, `--ds-ink-3` muted
- `--ds-radius`, `--ds-shadow`, and soft accents `--ds-soft-1/2/3` + `--ds-grad`

Rules:
- **Monochrome by default**: white surfaces, near-black text, grey secondary text, hairline borders, lots of whitespace. Clean and minimal — NOT dark, neon, or heavy gradients.
- Use a **subtle soft color or gentle gradient ONLY to highlight the 1–2 most important KPIs** (e.g. a `--ds-grad` card background or a thin accent bar) — everything else stays neutral grey/white.
- Charts: **use `deepsql.charts.*`** — they hover-tooltip, format, and handle empty data for you. Don't hand-write chart SVG.
- Numbers formatted for humans (`deepsql.charts.format(n)` / thousands separators; currency symbol from the business rule) — never raw.
- **Never render `undefined`, `null`, or `NaN`.** Guard every value you inject into the DOM (`v == null ? '—' : v`); a KPI sub-line/label with no value must fall back to a dash or be omitted — not the literal text "undefined".

## Avoid AI-slop patterns (named, so you can catch yourself)

These are the specific tells that make a generated dashboard look generated instead of
designed. Each one is easy to reach for by default — that's exactly why it needs to be named
and ruled out explicitly, not left to taste.

- **No default purple/blue/pink gradient backgrounds.** A `linear-gradient(135deg, #667eea, #764ba2)`-style
  hero band, header, or card is the single most recognizable AI-generated-UI signature. `--ds-grad`
  exists for exactly one purpose — a subtle lift on the single most important KPI card — never a page
  background, never a header banner, never more than one card on the whole dashboard.
- **No emoji as icons, bullets, or section markers.** Not in KPI labels, not in section headers, not
  as a substitute for a real icon. If a visual marker is needed, use a plain shape (a dot, a small
  colored square in a legend) — never 📊📈💰✨ etc.
- **No oversized rounded "blob" shapes, decorative background circles, or glassmorphism for its own
  sake.** `backdrop-filter`/translucency is not part of this theme — don't add it. Every visual
  element must carry information (a card, a chart, a legend swatch); nothing is decoration.
- **No uniform drop-shadow on every element.** `--ds-shadow` is for cards that sit on `--ds-bg` —
  don't add extra shadows to buttons, badges, or text, and don't stack multiple shadow layers for
  "depth." Flat and quiet is correct here.
- **No arbitrary one-off spacing or font sizes.** Pick from a small fixed scale and stay on it for
  the whole document:
  - Spacing: `4px 8px 12px 16px 24px 32px` — nothing between these, nothing larger without a real reason.
  - Type: 3 sizes total — a KPI number (~28–32px, bold), section/card headings (~14–15px, semibold),
    body/labels (~12–13px, regular). Don't introduce a fourth size for a one-off caption.
- **No centered "hero" layout with everything stacked in one narrow column.** This is a working
  dashboard, not a landing page — use a real grid (`auto-fit`/`auto-fill` KPI row, multi-column chart
  layout) that uses the available width purposefully.
- **Chart color discipline:** stick to the theme's own greyscale chart palette (already built into
  `deepsql.charts.*` — you don't choose chart colors). Don't override `opts.color` per chart to
  introduce your own arbitrary hues; the built-in palette is the whole point of using the shared
  chart runtime instead of hand-rolled SVG.
- **One hero KPI, not a "hero row."** If more than one card gets the gradient/accent treatment,
  none of them read as important — that defeats the point. Pick the single number the business
  question is actually about and reserve the accent for it alone.

Before emitting, ask: **would this ship, unedited, from a design team that obsesses over every
pixel — or does it look like the first thing a template generator produced?** If any of the
patterns above are present, that's your answer.

## Interaction

- Wire controls to re-run only the affected queries and re-render — never reload the page. A date range picker defaults to what the user asked for (e.g. today) and drives every time-sensitive query. A control that affects multiple widgets lives in the shell as a shared value (e.g. `window.__dateRange`); each widget's own script reads it and re-queries when it changes (e.g. listen for a `CustomEvent` the control dispatches on the shell's `document`) — a widget never assumes another widget's DOM exists.
- **Every widget loads independently** — its own `deepsql.query` call, its own `try/catch`. A slow or failing widget must NEVER block or fail the others; this is automatic once each widget is its own self-contained block.
- On a failed or timed-out query (`deepsql.query` rejects — the error text is e.g. "Timed out"), show a **quiet inline placeholder in that widget only** ("Timed out" / "Couldn't load this metric"), keep the rest of the dashboard working, and don't retry in a loop.

## Skeleton to adapt (design freely; uses the injected theme)

Shell — chrome and empty slots, no query logic:

```dashboard-shell
<!doctype html><html><head><meta charset="utf-8"><style>
  body{padding:28px}
  .headerbar{display:flex;justify-content:space-between;align-items:flex-start;gap:24px;flex-wrap:wrap;
    background:var(--ds-surface);border:1px solid var(--ds-line);border-radius:var(--ds-radius);
    padding:20px 24px;box-shadow:var(--ds-shadow);margin-bottom:22px}
  .eyebrow{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--ds-ink-2);margin:0 0 6px}
  .headerbar h1{font-size:26px;font-weight:700;margin:0 0 6px}
  .headerbar p{color:var(--ds-ink-2);margin:0;max-width:520px;font-size:13px;line-height:1.5}
  .controls{display:flex;align-items:flex-end;gap:20px;flex-wrap:wrap}
  .control-group label{display:block;font-size:11px;letter-spacing:.06em;text-transform:uppercase;
    color:var(--ds-ink-2);margin-bottom:6px}
  .quick-range{display:flex;gap:6px}
  .quick-range button{border:1px solid var(--ds-line);background:var(--ds-bg);color:var(--ds-ink);
    border-radius:8px;padding:8px 12px;font-size:13px;cursor:pointer}
  .quick-range button.active{background:var(--ds-ink);color:var(--ds-bg);border-color:var(--ds-ink)}
  .control-group input[type=date]{border:1px solid var(--ds-line);border-radius:8px;padding:7px 10px;font-size:13px}
  .apply-btn{border:1px solid var(--ds-line);background:var(--ds-bg);color:var(--ds-ink);
    border-radius:8px;padding:8px 14px;font-size:13px;cursor:pointer;align-self:flex-end}
  .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin:22px 0}
  .card{background:var(--ds-surface);border:1px solid var(--ds-line);border-radius:var(--ds-radius);padding:20px;box-shadow:var(--ds-shadow)}
  .card.hero{background:var(--ds-grad)}                 /* highlight the key KPI only */
  .kpi-val{font-size:32px;font-weight:700}
  .kpi-lab{color:var(--ds-ink-2);font-size:13px;margin-top:4px}
  .err{color:#b91c1c;font-size:13px}
</style></head><body>
  <div class="headerbar">
    <div>
      <p class="eyebrow">Daily performance</p>
      <h1>Booking Momentum</h1>
      <p>Daily new properties and booking volume across the selected period.</p>
    </div>
    <div class="controls">
      <div class="control-group">
        <label>Quick range</label>
        <div class="quick-range" id="quick-range">
          <button type="button" data-days="7">Last 7 days</button>
          <button type="button" data-days="30" class="active">Last 30 days</button>
          <button type="button" data-days="90">Last 90 days</button>
        </div>
      </div>
      <div class="control-group"><label>Start date</label><input type="date" id="date-from"></div>
      <div class="control-group"><label>End date</label><input type="date" id="date-to"></div>
      <button type="button" class="apply-btn" id="apply-range">Apply range</button>
    </div>
  </div>
  <div class="kpis">
    <div class="card hero" data-widget="new-properties"></div>
    <div class="card" data-widget="bookings-total"></div>
  </div>
  <div data-widget="bookings-trend"></div>
  <script>
    // Shared control: lives in the shell (not any one widget). Quick-range buttons
    // set both date inputs and apply immediately; typing dates directly only
    // applies on the explicit button, so a widget never re-queries mid-keystroke.
    // Every date-sensitive widget listens for 'dsql:daterange' to re-query.
    (function(){
      const fromEl = document.getElementById('date-from');
      const toEl = document.getElementById('date-to');
      const quickRange = document.getElementById('quick-range');
      const fmt = (d) => d.toISOString().slice(0,10);
      function setRange(days){
        const to = new Date();
        const from = new Date();
        from.setDate(from.getDate() - (days - 1));
        fromEl.value = fmt(from);
        toEl.value = fmt(to);
      }
      function apply(){
        window.__dateRange = { from: fromEl.value, to: toEl.value };
        document.dispatchEvent(new CustomEvent('dsql:daterange', { detail: window.__dateRange }));
      }
      quickRange.addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-days]');
        if (!btn) return;
        quickRange.querySelectorAll('button').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        setRange(Number(btn.dataset.days));
        apply();
      });
      document.getElementById('apply-range').addEventListener('click', () => {
        quickRange.querySelectorAll('button').forEach(b => b.classList.remove('active'));
        apply();
      });
      setRange(30);
      window.__dateRange = { from: fromEl.value, to: toEl.value };
    })();
  </script>
</body></html>
```

One block per widget — its own markup AND the script that queries and fills it in. A widget whose data is date-sensitive reads `window.__dateRange` on load and re-queries on the shell's `dsql:daterange` event; a widget that isn't date-sensitive (e.g. a lifetime total) simply ignores the control:

```dashboard-widget id="new-properties"
<p class="kpi-lab">New properties</p>
<p class="kpi-val" id="new-properties-val">—</p>
<script>
  async function load(){
    try {
      const { from, to } = window.__dateRange;
      const { rows } = await deepsql.query(
        `SELECT COUNT(*) FROM public.properties p WHERE p.created_at BETWEEN '${from}' AND '${to}'`);
      document.getElementById('new-properties-val').textContent = deepsql.charts.format(rows?.[0]?.[0] ?? 0);
    } catch (e) {
      document.getElementById('new-properties-val').textContent = '—';
    }
  }
  deepsql.ready(load);
  document.addEventListener('dsql:daterange', load);
</script>
```

```dashboard-widget id="bookings-trend"
<p class="kpi-lab">Bookings over time</p>
<div id="bookings-trend-chart"></div>
<script>
  async function load(){
    try {
      const { from, to } = window.__dateRange;
      const res = await deepsql.query(
        `SELECT ... FROM public.bookings b WHERE b.created_at BETWEEN '${from}' AND '${to}' ORDER BY 1`);
      deepsql.charts.line(document.getElementById('bookings-trend-chart'), res, { title: 'Bookings over time' });
    } catch (e) {
      document.getElementById('bookings-trend-chart').innerHTML =
        '<div class="err">' + (e.message === 'Timed out' ? 'Timed out.' : "Couldn't load.") + '</div>';
    }
  }
  deepsql.ready(load);
  document.addEventListener('dsql:daterange', load);
</script>
```

## Guardrails

- Read-only SELECT/WITH only (the bridge rejects anything else anyway).
- Self-contained: no external network, no imported fonts, no inline data dumps — query live.
- No internals visible to the user (see the security section above) — user-facing error text stays generic ("Couldn't load this metric.").
- Emit the shell ONCE, then one `dashboard-widget` block per widget (or a corrected re-emit of one, same id, if self-review catches a problem) — never wrap everything back into a single ```html block. On an EDIT, re-emit the full shell (even for widgets you aren't changing, their slots must still exist) and every widget's block, including unchanged ones — the assembled document is built fresh from what you emit this turn, not merged with the prior one.
