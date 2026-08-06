---
name: dashboard-design
description: Design and code a self-contained HTML dashboard for DeepSQL — ground on the schema, verify SQL, then write a beautiful single-file dashboard that loads data via the deepsql.query bridge.
version: 2.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [dashboard, bi, kpi, chart, html, date-range, deepsql, database]
    related_skills: [bi-query, schema-exploration]
---

# Dashboard Design

Use when asked to build or edit a dashboard (the task says "build a self-contained HTML dashboard"). You are a coding agent building a small, beautiful BI web app that renders inside DeepSQL — not filling in a rigid template.

## The runtime you build against

Your output is ONE self-contained HTML document. It runs inside a sandboxed iframe with a bridge already injected:

```js
deepsql.connectionId                      // this connection's id (string)
await deepsql.query("SELECT ...")         // -> { columns: string[], rows: any[][] }  (read-only, access-scoped)
deepsql.ready(fn)                          // runs fn() once the bridge is live (use this to kick off loading)

// Charts — ALWAYS use these instead of hand-writing SVG. Built-in hover tooltips
// (show the value on mouse-over), number formatting, sparse axis labels, and a
// graceful "No data" empty state. Pass the deepsql.query result straight in, or
// [{label,value}] / [[label,value]]. First column = label, second = value (or
// opts.labelKey/valueKey). opts: { valueFormat(fn), height, color, emptyText }.
deepsql.charts.bar(elOrSelector, data, opts)     // rankings, counts by day
deepsql.charts.line(elOrSelector, data, opts)     // trends over time (area+line)
deepsql.charts.donut(elOrSelector, data, opts)    // share/composition (with legend + %)
deepsql.charts.format(n)                           // human number formatter
```

Hard rules:
- Inline everything — one `<style>`, one or more `<script>`. **No external URLs, CDNs, fonts, or images** (blocked by CSP) and **no `fetch()`/XHR/WebSocket** — data comes only from `deepsql.query`.
- Never hardcode result data. Query live on load, and re-query when a control changes.
- There is **no placeholder convention**. You write normal SQL strings in JS and pass the finished string to `deepsql.query`. Build date filters yourself (see below).

## Procedure

1. **Ground.** `get_brain_context`, `get_schema`, `list_business_rules`, `get_relationships`. Obey business rules about which table/column/filter/currency a concept uses — quote them; don't guess a similar-looking table.
2. **Design.** Decide the KPIs, charts, tables, and controls (date range, dropdowns) the request calls for. Sketch the SQL for each — table-qualified, read-only.
3. **Handle dates correctly.** Check the column's type in the schema. If it's a real DATE/DATETIME, filter with `BETWEEN '2026-07-01' AND '2026-07-08'`. **If it's a Unix-epoch integer** (seconds), filter on the epoch: `col >= UNIX_TIMESTAMP('2026-07-01 00:00:00') AND col < UNIX_TIMESTAMP('2026-07-09 00:00:00')`. Build these strings in JS from the picker's values.
4. **Verify.** Run every query with `execute_sql` and READ the rows: date windows bounded and inside range (never the future), KPI value types right (name = text, money = currency), totals plausible vs a `COUNT(*)`. Fix and re-run until correct.
5. **Intent checklist.** Before emitting, list every explicit ask (each chart, each metric, each control like "a date range picker defaulting to today") and confirm the HTML satisfies ALL of them. An unmet ask is a failed dashboard even if the data is perfect.
6. **Code the document** (see skeleton), using `deepsql.charts.*` for every chart.
7. **Self-review before emitting** — reread your finished HTML as if you were the user opening it, and fix anything that fails this checklist:
   - Every widget has a real data source and a query you actually verified; no placeholder/lorem values.
   - Every chart uses `deepsql.charts.*` (so it has hover tooltips) and passes the correct label/value columns — no chart left blank because the data shape didn't match.
   - No `undefined` / `null` / `NaN` can reach the screen — every injected value is guarded with a fallback. Pay special attention to KPI sub-labels and any computed % (e.g. a "top source share" caption).
   - Every explicit user ask from the intent checklist is present and wired (controls default correctly and re-query on change).
   - No table/column/SQL/connection-id text is visible anywhere.
   A dashboard that renders with a blank chart or an "undefined" label is a failed build — catch it here.

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

## Interaction

- Wire controls to re-run only the affected queries and re-render — never reload the page. A date range picker defaults to what the user asked for (e.g. today) and drives every time-sensitive query.
- **Load each widget independently and in parallel** — one `deepsql.query` per widget, each with its own `try/catch`. A slow or failing widget must NEVER block or fail the others.
- On a failed or timed-out query (`deepsql.query` rejects — the error text is e.g. "Timed out"), show a **quiet inline placeholder in that widget only** ("Timed out" / "Couldn't load this metric"), keep the rest of the dashboard working, and don't retry in a loop.

## Skeleton to adapt (design freely; uses the injected theme)

```html
<!doctype html><html><head><meta charset="utf-8"><style>
  body{padding:28px}
  .head h1{font-size:26px;font-weight:700;margin:0 0 6px}
  .head p{color:var(--ds-ink-2);margin:0}
  .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin:22px 0}
  .card{background:var(--ds-surface);border:1px solid var(--ds-line);border-radius:var(--ds-radius);padding:20px;box-shadow:var(--ds-shadow)}
  .card.hero{background:var(--ds-grad)}                 /* highlight the key KPI only */
  .kpi-val{font-size:32px;font-weight:700}
  .kpi-lab{color:var(--ds-ink-2);font-size:13px;margin-top:4px}
  .err{color:#b91c1c;font-size:13px}
</style></head><body>
  <div class="head"><h1>Booking Momentum</h1><p>Daily new properties and booking volume for the selected dates.</p></div>
  <div class="controls"><!-- date range, defaulting to today --></div>
  <div class="kpis" id="kpis"></div>
  <div id="charts"></div>
  <script>
    async function loadKpi(el, sql, fmt){ try{ const {rows}=await deepsql.query(sql);
      const v = rows?.[0]?.[0]; el.querySelector('.kpi-val').textContent = (v==null?'—':fmt(v)); }
      catch(e){ el.innerHTML='<div class="err">Couldn\\'t load this metric.</div>'; } }
    async function loadChart(el, sql){ try{ const res=await deepsql.query(sql);
      deepsql.charts.bar(el, res); }                 // hover tooltips + formatting built in
      catch(e){ el.innerHTML='<div class="err">'+(e.message==='Timed out'?'Timed out.':'Couldn\\'t load.')+'</div>'; } }
    deepsql.ready(async () => { /* set date range to today, then load every widget independently */ });
  </script>
</body></html>
```

## Guardrails

- Read-only SELECT/WITH only (the bridge rejects anything else anyway).
- Self-contained: no external network, no imported fonts, no inline data dumps — query live.
- No internals visible to the user (see the security section above) — user-facing error text stays generic ("Couldn't load this metric.").
- Return the FULL document every time (including on edits), inside ONE ```html block, with no prose after it.
