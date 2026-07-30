# DeepSQL Design Guidelines

> **Audience:** Claude (and any other AI agent or human designer) producing
> DeepSQL marketing surfaces — landing pages, microsites, one-pagers, pitch
> decks, sales decks, blog headers, social cards, ads, conference banners,
> sticker art, and demo assets.
>
> **Authority:** This document is the source of truth for DeepSQL marketing
> design. It inherits and extends the product-side UX guidelines in
> [`docs/root/CLAUDE.md`](root/CLAUDE.md) so the product and the marketing feel
> like the same company. When the two conflict, the product guide wins for
> in-app surfaces and this guide wins for marketing surfaces.
>
> **How Claude should use this doc:** Read the section that matches the
> deliverable (e.g., "Pitch deck templates" before making a .pptx, "Landing
> page sections" before writing HTML/JSX). Lift tokens verbatim — do not
> invent new colors, fonts, or radii. When a constraint here forces a
> trade-off, note it in the response so the user can override deliberately.

---

## 1. Brand foundation

### 1.1 What DeepSQL is

DeepSQL is the **brain for databases**. It does four things and nothing else:

1. Answers natural-language questions about any database in plain English.
2. Autonomously diagnoses performance problems the way a senior DBA would.
3. Guards before it executes — read-only enforcement, wrong-table protection,
   safety guardrails.
4. Integrates where teams already work — Slack, MCP for Claude/Codex, REST.

Every marketing surface must make at least one of these four jobs legible
inside the first viewport.

### 1.2 Positioning sentence (load-bearing)

> "The database has always been the source of truth. DeepSQL makes it the
> source of answers — autonomously, safely, and at a scale no human DBA
> team could match."

This sentence is the canonical positioning. Headlines may riff on it but
should not contradict it. Do not weaken it with hedges like "tries to,"
"helps you," or "AI-powered assistant for…".

### 1.3 Brand personality

| Trait | We are | We are not |
| --- | --- | --- |
| **Confident** | A senior DBA who's seen the incident before | A chatty assistant asking permission |
| **Technical** | Comfortable with EXPLAIN plans, WALs, locks, bloat | "Democratizing data" buzzword soup |
| **Restrained** | Black, white, grey, one accent if at all | Gradient mesh, neon, glassmorphism |
| **Operational** | Numbers, before/after, p99, ms saved | Vibes, vague benefits, "synergy" |
| **Safe** | Read-only by default, guardrails first | "Move fast" cowboy energy |

### 1.4 Audiences (for tone calibration)

- **DB engineers / SREs / DBAs** — primary. Skeptical. Want proof. Tone:
  technical, specific, EXPLAIN plans welcome.
- **Eng leaders / CTOs** — secondary. Want ROI, safety, team velocity.
  Tone: outcomes + receipts.
- **Data / BI / analyst leads** — tertiary. Want self-serve answers.
  Tone: less SQL, more "answers without a ticket."
- **VCs / press** — context-dependent. Use the positioning sentence
  verbatim and lead with traction.

If you don't know the audience, default to DB engineers.

---

## 2. Voice & tone

### 2.1 Voice (always on)

- **Declarative.** "DeepSQL caught a missing index on `orders.user_id` in
  3.2 seconds." Not "DeepSQL can sometimes help identify…".
- **Specific.** Cite tables, columns, query times, row counts, p99s. If a
  number is available, use it.
- **Short.** Prefer 6-12 word sentences in headlines, 18-24 in body.
- **Tool-fluent.** Mention `EXPLAIN ANALYZE`, `pg_stat_statements`, WAL,
  vacuum, AUTOVACUUM, locks, B-tree, GIN — only when they earn their keep.
- **Honest about scope.** DeepSQL guards before it executes. Say so.
  Avoid promising AGI-tier autonomy.

### 2.2 Tone by surface

| Surface | Tone dial |
| --- | --- |
| Landing hero | Confident, almost terse. One sentence does the work. |
| Feature sections | Operator-to-operator. "Here's what happens. Here's the proof." |
| Pricing page | Direct, no upsell theater. State what each tier does. |
| Pitch deck | Story arc: problem → why now → what we built → traction → ask. |
| Demo captions | Narration of what's on screen, in present tense. |
| Error / empty states | Calm, factual, never cute. "No slow queries in the last 24h." |
| Social posts | One claim + one receipt (screenshot, number, link). |

### 2.3 Words we use

`autonomously`, `verified`, `read-only`, `guardrail`, `EXPLAIN`, `index`,
`bloat`, `lock`, `p99`, `seconds`, `query`, `schema`, `Slack`, `MCP`,
`Postgres`, `MySQL`, `DBA`, `incident`, `source of truth`, `source of answers`.

### 2.4 Words we avoid

`revolutionary`, `cutting-edge`, `next-gen`, `unleash`, `empower`,
`democratize`, `seamless`, `magical`, `AI-powered` (overused — say what it
does), `co-pilot` (taken), `copilot for databases`, `ChatGPT for SQL`.

### 2.5 Copy templates

- **Headline (capability):** `[Verb] [outcome] [proof noun].` →
  "Diagnose slow queries before the pager fires."
- **Subhead:** `[Audience] use DeepSQL to [job] without [pain].` →
  "Engineering teams use DeepSQL to answer database questions without
  writing SQL or filing a DBA ticket."
- **Feature block:** `[Specific behavior]. [Specific receipt].` →
  "Catches missing indexes on hot queries. Found 4 on the Blazel staging
  cluster in the first hour."
- **CTA:** Verbs only. `Try DeepSQL`, `Talk to Venkat`, `Read the docs`,
  `Install the Slack bot`. Avoid `Learn more` and `Get started` —
  too vague.

---

## 3. Color tokens

The marketing palette is the product palette. Do not introduce new hues.

### 3.1 Core palette (Tailwind-aligned)

| Token | Hex | Tailwind | Use |
| --- | --- | --- | --- |
| `ink` | `#111827` | `gray-900` | Primary text, primary button, logo |
| `ink-soft` | `#1F2937` | `gray-800` | Tooltip bg, dark deck slide bg |
| `text-secondary` | `#6B7280` | `gray-500` | Subhead, captions, labels |
| `text-tertiary` | `#9CA3AF` | `gray-400` | Placeholder, footnote |
| `border` | `#E5E7EB` | `gray-200` | Hairlines, card borders, dividers |
| `surface-1` | `#FFFFFF` | `white` | Page bg, card bg |
| `surface-2` | `#F9FAFB` | `gray-50` | Section bg, alt row |
| `surface-3` | `#F3F4F6` | `gray-100` | Code block bg, badge bg, hover |
| `danger` | `#DC2626` | `red-600` | Errors, destructive, before-state |
| `success` | `#059669` | `emerald-600` | Verified, after-state, on-state |

### 3.2 Accent (use sparingly)

DeepSQL does not have a brand "color" beyond ink-on-paper. When a single
accent is unavoidable (highlighting one metric, one CTA in a sea of
content, one line in a chart), use:

- `accent` = `#111827` first. Yes — black is the accent.
- If contrast against `ink` is needed (e.g., chart line over dark slide),
  use `#FFFFFF`.
- Only if a chart genuinely needs more than two series, use the data-viz
  ramp in §11.2.

**Never** use brand gradients, neon, holographic, or "AI purple/teal."
That category is saturated and we look like everyone else if we join it.

### 3.3 Dark mode (decks, social cards, t-shirts)

| Token | Hex | Use |
| --- | --- | --- |
| `dark-bg` | `#0B0F19` | Slide / card background |
| `dark-surface` | `#111827` | Cards on dark bg |
| `dark-border` | `#1F2937` | Hairlines on dark bg |
| `dark-text` | `#F9FAFB` | Body text on dark |
| `dark-text-secondary` | `#9CA3AF` | Captions on dark |

Dark mode is the **default for pitch decks** (executive audiences read
slides as cinema; light decks look like spreadsheets). Light mode is the
default for landing pages, docs, and blogs.

### 3.4 Contrast rules

- Body text on any background must hit WCAG AA (4.5:1).
- Hero text must hit AAA (7:1) — these are read at distance and on
  projectors.
- Never put `text-secondary` on `surface-2` (fails AA). Use `ink` or move
  to `surface-1`.

---

## 4. Typography

### 4.1 Type families

- **Primary:** Inter (variable weight). Same as the product.
- **Mono:** JetBrains Mono or `ui-monospace`, for SQL, code, query plans,
  and anywhere we display table/column names.
- **Display (decks only, optional):** Inter Tight at 600-700 for slide
  titles. Never a serif. Never a "personality" display font.

Font stack:

```css
font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI',
  'Roboto', 'Oxygen', 'Ubuntu', 'Cantarell', sans-serif;
font-family: 'JetBrains Mono', 'SF Mono', Menlo, Consolas, monospace;
```

### 4.2 Marketing type scale

| Role | Size (desktop) | Size (mobile) | Weight | Line height | Tracking |
| --- | --- | --- | --- | --- | --- |
| Hero headline | 64-80px | 40-44px | 600 | 1.05 | -0.02em |
| Section headline | 40-48px | 28-32px | 600 | 1.1 | -0.02em |
| Sub-headline | 22-24px | 18-20px | 400 | 1.4 | -0.01em |
| Eyebrow / kicker | 13-14px | 13px | 600 | 1.2 | +0.08em, UPPERCASE |
| Body | 17-18px | 16px | 400 | 1.55 | normal |
| Small / caption | 14px | 13px | 400 | 1.5 | normal |
| Code inline | 0.95em of parent | — | 500 | inherit | normal |
| Code block | 14-15px | 13px | 400 | 1.55 | normal |

### 4.3 Deck type scale (16:9, 1920×1080)

| Role | Size | Weight |
| --- | --- | --- |
| Slide title | 44-56pt | 600 |
| Slide subtitle | 22-26pt | 500 |
| Body bullet | 20-22pt | 400 |
| Caption / footnote | 14-16pt | 400 |
| Number / metric | 96-160pt | 700 |

Never let a slide body drop below 18pt. If it doesn't fit, the slide has
too much on it — split it.

### 4.4 Rules

- One font family per surface. Inter for everything text, JetBrains Mono
  for code. That's it.
- Headlines tighten tracking (`-0.02em`), body does not.
- Numbers in metrics use **tabular-nums** (`font-variant-numeric:
  tabular-nums`) so columns line up. Always.
- Never center-align body copy. Center hero headlines and CTAs only.
- Never use italics for emphasis — use weight (600) or a different color.

---

## 5. Spacing, grid, layout

### 5.1 Spacing scale (4px base)

`4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128, 160` — match the
product. Do not invent in-between values.

### 5.2 Section rhythm (landing pages)

- **Section vertical padding:** 96-128px desktop, 64-80px mobile.
- **Container max-width:** 1200px for prose-heavy sections, 1280-1360px
  for product-screenshot sections.
- **Gutter:** 24-32px desktop, 16-20px mobile.
- **Between heading and body:** 16-24px.
- **Between body and CTA:** 24-32px.

### 5.3 Grid

- **Landing:** 12-column, 24px gutter, 80px outer margin.
- **Feature trio:** 3 equal columns at desktop, stack at <768px.
- **Deck:** 12-column 16:9 grid (1920×1080). Outer safe area 64px on all
  sides. Title at y=120, content at y=280.

### 5.4 Border radius

Match the product:

- `4-6px` — badges, pills, inline tags.
- `8px` — buttons, inputs, small cards.
- `12px` — feature cards, modals.
- `16-20px` — hero illustration containers, screenshot frames.
- **Never** fully rounded (`9999px`) except on avatars and pill tags.

### 5.5 Elevation

- **None by default.** A 1px `border` line does the work of a shadow.
- **Subtle hover only:** `0 1px 3px rgba(0,0,0,0.06)` on cards.
- **Modal/overlay only:** `0 20px 40px rgba(0,0,0,0.15)`.
- Never stack multiple shadows. Never use colored shadows.

---

## 6. Logo & wordmark

DeepSQL does not have a finalized illustrative mark. Until it does:

### 6.1 Wordmark

- Word: **DeepSQL** — capital D, capital S, capital Q, capital L. No
  space, no hyphen, no lowercase. Never "Deep SQL" or "deepsql" in
  marketing copy (the package name `deepsql` is fine inside code blocks).
- Font: **Inter, 700**, tracking `-0.03em`.
- Color: `ink` on light, `dark-text` on dark.
- Optical correction: SQL is taller-looking than Deep — no manual kerning
  needed at Inter 700; trust the font.

### 6.2 Lockup with tagline

When a tagline is required (slide footers, business cards):

```
DeepSQL
brain for databases
```

- Tagline: Inter 500, 0.4× the wordmark size, `text-secondary` color,
  same baseline as wordmark's descender.
- Never set the tagline in ALL CAPS.

### 6.3 Clear space

Clear space around the wordmark = cap-height of the "D". Nothing inside
that zone.

### 6.4 Minimum size

- Screen: 16px cap height.
- Print / pitch deck: 0.5cm cap height.
- Favicon: solid `ink` square with white "D" set in Inter 700.

### 6.5 Do not

- Outline the wordmark.
- Drop-shadow the wordmark.
- Place over a busy photo without a solid plate.
- Recolor in brand-foreign colors (no red, blue, purple wordmarks).
- Stretch, italicize, or skew.

---

## 7. Iconography

- **Library:** Lucide (matches the product). 1.5px stroke.
- **Sizes:** 16 / 20 / 24 / 32 / 48px. Use 24px in marketing feature rows.
- **Color:** Same as adjacent text. Never multi-color icons.
- **Custom icons:** If Lucide doesn't have it, draw on a 24×24 grid,
  1.5px stroke, square caps, 2px corner radius on rounded corners.
- **Never** use 3D, isometric, or "neumorphic" icons. Never emoji as icons
  in product marketing surfaces (emoji are okay in social posts and
  internal Slack, not on the landing page).

Default DeepSQL icon vocabulary:

| Concept | Lucide icon |
| --- | --- |
| Question | `message-square` |
| Diagnose | `activity` |
| Guard | `shield-check` |
| Integrate | `plug` |
| Database | `database` |
| Query | `terminal` |
| Slow / risk | `alert-triangle` |
| Fast / verified | `check-circle-2` |
| Slack | brand mark (official) |
| MCP / Claude | the Anthropic logo if licensed; otherwise `sparkles` |

---

## 8. Imagery & illustration

DeepSQL's signature visual is **the database itself, rendered honestly**:
EXPLAIN plans, query plans as DAGs, table schemas, before/after numbers.
Stock photos of "people pointing at laptops" are banned.

### 8.1 Allowed imagery

- Real product screenshots, cropped to a single insight per image.
- EXPLAIN plan visualizations (node tree, costs annotated).
- Slack message screenshots of DeepSQL answering a question.
- Terminal recordings (asciinema-style) for MCP usage.
- Schema diagrams (boxes + 1px lines, no gradients).
- Numbered metric cards: "p99: 4.2s → 280ms" — see §11.

### 8.2 Banned imagery

- Stock photography of humans in offices.
- 3D-rendered "data spheres" or glowing nodes.
- AI-generated maximalist hero art.
- Hand-drawn whiteboard illustrations of "how AI works."
- Robot mascots.

### 8.3 Screenshot rules

- Crop to the smallest rectangle that proves the claim.
- Add a 1px `border` (`#E5E7EB`) and 12px radius — never a drop shadow.
- Redact real customer PII with a solid `surface-3` block (not blur).
- Pixel-double for retina export (2x).
- Annotate sparingly with `ink` arrows and labels — never red circles.

---

## 9. Motion

### 9.1 Duration & easing

Inherits product values:

- Fast: 150ms (hover, focus).
- Normal: 200-300ms (most interactions).
- Slow: 400-500ms (page transitions, hero reveals).
- Easing default: `cubic-bezier(0.4, 0, 0.2, 1)`.

### 9.2 Marketing-specific

- **Hero reveal:** Fade-up 16px over 400ms, stagger 60ms per element.
  No bounce. No spring overshoot.
- **Scroll-triggered:** One subtle reveal per section. Never more than
  one parallax layer. Never lock scroll.
- **Loading numbers (counters):** Animate from 0 to value over 600ms,
  ease-out. Use `tabular-nums` so digits don't jitter.
- **Demo loops:** Max 8 seconds. Loop seamlessly. No auto-playing audio.
- **Respect `prefers-reduced-motion`** — fall back to opacity-only.

### 9.3 Never

- Confetti, sparkles, "AI shimmer."
- Auto-rotating carousels.
- Marquee tickers (unless it's a logo wall and even then, prefer a
  static grid).
- Cursor-following gradient blobs.

---

## 10. Landing page section patterns

A DeepSQL landing page is composed from a small library of section
templates. Pick the minimum number of sections that tell the story; a
typical homepage is 5-7 sections.

### 10.1 Hero

- **Eyebrow** (optional): "Brain for databases." (8-14 chars max.)
- **Headline** (≤ 9 words): the job, declaratively. E.g., "Ask your
  database in plain English."
- **Subhead** (1-2 sentences, ≤ 28 words): what + who + why now.
- **Primary CTA:** verb + product noun. "Try DeepSQL" / "Install in
  Slack" / "Get the MCP server."
- **Secondary CTA:** ghost button. "Talk to Venkat" / "Read the docs."
- **Hero proof:** a single screenshot, terminal recording, or chat
  bubble showing DeepSQL answering. Never a generic "dashboard."
- Layout: text left, proof right at desktop; stack at mobile. No
  centered hero unless the proof is full-bleed.

### 10.2 The four jobs

A canonical section that maps to the product. Four cards or a 2×2 grid:

1. **Answers questions in plain English.** ← chat screenshot
2. **Diagnoses performance autonomously.** ← EXPLAIN/metric screenshot
3. **Guards before it executes.** ← read-only badge + denied write
4. **Integrates where you work.** ← Slack + MCP + REST logos

Each card: icon (24px) → 4-6 word title → 1-2 sentence body → 1 proof.

### 10.3 Proof / receipts

The most important section. At least one of:

- **Metric strip:** 3-4 numbers with before/after. "p99 4.2s → 280ms",
  "First missing index found in 3.2s", "0 destructive writes in
  10,000+ runs", "23min → 90s to root cause."
- **Customer logo wall:** static grid, monochrome at 60% opacity.
  Only logos we have written permission for.
- **Quote card:** ≤ 30 words, attributed with name + title + company.
  Headshot optional (1:1, b/w, on `surface-2`).

### 10.4 How it works

3-step diagram. Each step is one sentence + one icon. Never more than
five steps; if you need more, it's not a "how it works" section, it's
docs.

### 10.5 Integrations

A grid (4-6 tiles): Slack, Claude (MCP), Codex, REST API, Postgres,
MySQL. Each tile is a logo on `surface-2`, 12px radius, 1px border.
Click → respective docs page.

### 10.6 Compare / "DeepSQL vs."

When competitive framing is needed, use a 3-column comparison: feature
list on the left, DeepSQL column in `ink`, competitor column in
`text-secondary`. Use `check-circle-2` and `x` icons, never thumbs.
Be specific and accurate — exaggerated comparison tables damage trust.

### 10.7 Pricing

- 3-tier max: Free / Team / Enterprise. State concrete limits, not
  "unlimited\*". State what's included, not what's excluded.
- Recommended tier is highlighted with a 2px `ink` border, not a color.
- "Talk to sales" only for Enterprise. Free and Team should be
  self-serve.

### 10.8 CTA band

Full-width, `ink` background, `dark-text` headline + one button. One
sentence: restate the headline as a directive. "Stop chasing slow
queries. Start asking your database." → button: "Try DeepSQL."

### 10.9 Footer

- Wordmark + tagline (top-left).
- Three columns max: Product / Docs / Company.
- Compliance row: SOC 2 status, security page link, privacy, terms.
- Copyright: `© 2026 DeepSQL (a Stayflexi project)` until the entity
  is separated; update when that changes.

---

## 11. Data, charts, tables, SQL

DeepSQL's product is data. Marketing surfaces must render data with
unusual care.

### 11.1 Number display

- **Big metric:** 96-160pt, weight 700, `ink` (or `dark-text` on dark),
  `tabular-nums`. Below: 14-16pt `text-secondary` label.
- **Before/after pair:** show both numbers with an arrow between them.
  Use `danger` for the before-number and `success` for the after-number
  — this is the **only** sanctioned use of accent color outside charts.
  Always include units. "**4.2s** → **280ms**".
- **Percent change:** include the sign and the unit. "−93%". Use the
  Unicode minus (−), not hyphen.

### 11.2 Charts

- One series → `ink` (light bg) or `dark-text` (dark bg).
- Two series → `ink` + `text-secondary`.
- Three or four series → add `#9CA3AF` and `#D1D5DB`. If you need five,
  rethink the chart.
- Grid lines: 1px `border`. No vertical grid lines on time-series.
- Axes: 12-14px `text-secondary`. No 3D, no shadows, no gradients on
  bars.
- Annotations (labels on the chart itself) beat legends. Skip the
  legend whenever you can label inline.
- Always label units on the axis or in the title. Never make readers
  guess between ms and s.

### 11.3 Tables

- Header row: 12-13px UPPERCASE, `text-secondary`, weight 600,
  letter-spacing +0.04em.
- Body: 14-15px, `ink`, weight 400, `tabular-nums` for any column with
  numbers.
- Row divider: 1px `border`. No alternating row colors in marketing
  tables (busy).
- Comparison columns use `check-circle-2` (`success`) and `x`
  (`text-tertiary`). Never green check + red X — the X is "not
  included", not "bad."

### 11.4 SQL / code display

- Background: `surface-3` (light) or `dark-surface` (dark). Never
  `ink` on light — too heavy.
- Font: JetBrains Mono, 14-15px, weight 400.
- Syntax highlighting: muted. Keywords in `ink` weight 600, strings in
  `text-secondary`, comments in `text-tertiary` italic. No rainbow.
- Padding: 16-20px. Radius: 8-12px.
- Show a copy button (top-right) when interactive. Never auto-execute.
- For multi-line SQL, prefer SQL-formatted (one clause per line,
  uppercase keywords) — we are credible to DBAs by formatting like a DBA.

Example:

```sql
SELECT u.id, u.email, COUNT(o.id) AS orders
FROM users u
LEFT JOIN orders o ON o.user_id = u.id
WHERE u.created_at > NOW() - INTERVAL '30 days'
GROUP BY u.id, u.email
ORDER BY orders DESC
LIMIT 10;
```

### 11.5 EXPLAIN plan rendering

When showing a plan, render as a vertical tree with monospace nodes.
Annotate cost and rows in `text-secondary` to the right of each node.
Highlight the offending node (the one DeepSQL flagged) with a 2px
`ink` left-border. Never use a red box.

---

## 12. Pitch deck templates

Standard format: 16:9, 1920×1080, dark mode by default.

### 12.1 Slide types & rules

| Slide | Rule of thumb |
| --- | --- |
| **Title** | Wordmark center, tagline below, date + venue in `dark-text-secondary` bottom-left. No decoration. |
| **Section divider** | Single word or phrase, 96pt+, centered, on `dark-bg`. Used to pace the deck. |
| **Problem** | One sentence at top (44pt), one supporting stat (160pt number), one source line (14pt) at bottom. |
| **Insight / why now** | Two halves: left = old way, right = new way. Use the before/after pattern from §11.1. |
| **Product** | One product screenshot, full-bleed with 64px margin. Caption in `dark-text-secondary` below. |
| **Demo placeholder** | Single-word title "Demo." Embed a recording or note "live demo" — never use a fake screen. |
| **Architecture** | Boxes + 1px lines, all `dark-text`. No icons inside boxes unless they earn it. |
| **Traction** | Numbers slide. 3-4 metrics in a row. `success` for growth, `dark-text` for absolute counts. |
| **Customer quote** | Quote in 32-40pt, attribution in 18pt, on `dark-bg`. No headshot unless it's a marquee logo. |
| **Team** | Names, roles, prior companies. Square b/w headshots, 200px, on `dark-surface`. |
| **Ask** | One sentence. The number. Use of funds in 3-4 bullets. End with contact. |
| **Thank you / contact** | Wordmark + email + URL. No "Q&A" slide — questions happen in person. |

### 12.2 Universal deck rules

- **One idea per slide.** If you can't say it in the title, it's two
  slides.
- **Title cases as sentences.** "How DeepSQL diagnoses slow queries."
  Not "How DeepSQL Diagnoses Slow Queries."
- **No bullet pyramids.** Replace nested bullets with two slides or a
  diagram.
- **Page numbers** in `dark-text-secondary` 14pt, bottom-right. Skip on
  title and divider slides.
- **Slide footer** on every content slide: wordmark + page number, no
  date. Investors share decks — date in the corner ages badly.
- **Fonts:** Inter for everything, JetBrains Mono for code. Both must
  be embedded in the .pptx (use Calibri/Consolas as fallbacks for
  PowerPoint platforms without the fonts).

### 12.3 Deck cadence (investor deck, ~12-14 slides)

1. Title
2. Problem (one number)
3. Why now (one number)
4. What is DeepSQL (positioning sentence)
5. How it works (3 steps)
6. Product (screenshot 1 — chat)
7. Product (screenshot 2 — diagnosis)
8. Product (screenshot 3 — guard)
9. Integrations
10. Traction
11. Market / why we win
12. Team
13. Ask
14. Contact

Drop slides 11 or 12 if the meeting is short. Never drop traction.

### 12.4 Sales deck (~8-10 slides)

Same vocabulary, different order: problem → product → proof → pricing →
next step. Skip team and ask. End on "next step," not "thank you."

---

## 13. Marketing surfaces — quick rules

### 13.1 Social cards (Twitter/X, LinkedIn, 1200×630)

- Dark background.
- Wordmark top-left, 48px clear space.
- One line of message, 56-72pt, weight 600, max two lines.
- Optional metric chip bottom-right.
- Never include the author's headshot — the message is the thing.

### 13.2 Blog post headers (1600×800)

- Light or dark, decide per series and stay consistent.
- Title set as if it were a section headline (§4.2). No subtitle in
  the hero image — leave that for the post body.

### 13.3 One-pager (PDF)

- A4 / US Letter, single page.
- Top: wordmark + positioning sentence.
- Middle: the four jobs as a 2×2 grid.
- Bottom: traction numbers + contact + URL.
- Print-safe: minimum 12pt body, 0.5" margins. CMYK with `ink` mapped
  to rich black (`60-40-40-100`).

### 13.4 Conference banner / pull-up (85×200cm)

- Wordmark + tagline at viewing height (~150cm from ground).
- One headline. One QR code linking to a tracked landing URL.
- Nothing else. Booth banners read at 3m and 0.5m only.

### 13.5 Stickers / t-shirts (when we do them)

- Wordmark only, or a single SQL line in mono (`SELECT * FROM peace;`
  energy, but real and relevant). Never put a roadmap on a sticker.

---

## 14. Accessibility

- WCAG AA minimum, AAA for hero text.
- Every interactive element gets a visible focus ring: 2px `ink`
  outline, 2px offset.
- All non-decorative images get descriptive alt text. Screenshots:
  describe what DeepSQL is doing, not just "screenshot of UI."
- Decks and PDFs include actual selectable text — never rasterize a
  slide.
- Captions on any video demo. No reliance on color alone (before/after
  uses position + arrow + label, not just red→green).
- Touch targets ≥ 44×44px.

---

## 15. File & asset conventions

- File naming: `kebab-case-with-context.ext`. E.g.
  `deepsql-hero-light-2026-05.png`,
  `pitch-deck-yc-summer-2026.pptx`,
  `one-pager-enterprise-v3.pdf`.
- Versioning: append `-vN` when iterating; archive prior version,
  don't overwrite.
- Storage: marketing assets live in `dbaagent/marketing/` (when
  created) or in the shared DeepSQL workspace folder. Final PDFs and
  decks go to the user's selected folder so Venkat can grab them.
- Image exports: PNG for screenshots (lossless), SVG for diagrams and
  icons, MP4 (H.264) for demo videos at 1080p, 30fps, < 8s when
  embedded in pages.
- Font embedding: always embed Inter and JetBrains Mono in PDF and
  PPTX exports. Without embedding, slides reflow on the reviewer's
  machine.

---

## 16. Tailwind & CSS token reference

For web surfaces, use these utility shortcuts (mirrors product config):

```html
<!-- Surfaces -->
<div class="bg-white">       <!-- surface-1 -->
<div class="bg-gray-50">     <!-- surface-2 -->
<div class="bg-gray-100">    <!-- surface-3 -->
<div class="bg-gray-900">    <!-- ink (dark band, CTA band) -->
<div class="bg-[#0B0F19]">   <!-- dark-bg, decks/cards -->

<!-- Text -->
<p class="text-gray-900">    <!-- primary -->
<p class="text-gray-500">    <!-- secondary -->
<p class="text-gray-400">    <!-- tertiary -->

<!-- Borders -->
<div class="border border-gray-200 rounded-lg">

<!-- Buttons -->
<button class="bg-gray-900 text-white rounded-lg px-5 py-3
               text-base font-medium hover:bg-gray-800
               transition-colors duration-200">
  Try DeepSQL
</button>

<button class="border border-gray-200 text-gray-900 rounded-lg
               px-5 py-3 text-base font-medium
               hover:bg-gray-50 transition-colors duration-200">
  Talk to Venkat
</button>

<!-- Hero headline -->
<h1 class="text-5xl md:text-7xl font-semibold tracking-tight
           text-gray-900 leading-[1.05]">
  Ask your database in plain English.
</h1>

<!-- Section headline -->
<h2 class="text-3xl md:text-5xl font-semibold tracking-tight
           text-gray-900 leading-tight">
  ...
</h2>

<!-- Metric -->
<div class="font-semibold tabular-nums text-6xl md:text-8xl
            text-gray-900">280<span class="text-3xl
            text-gray-500 ml-1">ms</span></div>
```

CSS custom properties (for non-Tailwind contexts):

```css
:root {
  --ds-ink: #111827;
  --ds-ink-soft: #1F2937;
  --ds-text-secondary: #6B7280;
  --ds-text-tertiary: #9CA3AF;
  --ds-border: #E5E7EB;
  --ds-surface-1: #FFFFFF;
  --ds-surface-2: #F9FAFB;
  --ds-surface-3: #F3F4F6;
  --ds-danger: #DC2626;
  --ds-success: #059669;
  --ds-dark-bg: #0B0F19;
  --ds-dark-surface: #111827;
  --ds-dark-text: #F9FAFB;
  --ds-radius-sm: 6px;
  --ds-radius-md: 8px;
  --ds-radius-lg: 12px;
  --ds-radius-xl: 20px;
  --ds-ease: cubic-bezier(0.4, 0, 0.2, 1);
}
```

---

## 17. Anti-patterns (firm "no")

- ❌ Purple/teal AI gradients. Black, white, grey. Always.
- ❌ Robot mascots, glowing brain illustrations, neural-net hero art.
- ❌ "Magical," "revolutionary," "unleash," "co-pilot," "ChatGPT for X."
- ❌ Stock photos of humans pointing at screens.
- ❌ Glassmorphism, neumorphism, heavy drop shadows, 3D bevels.
- ❌ More than one font family. More than two type weights per surface.
- ❌ Centered body copy. Centered long-form paragraphs.
- ❌ Auto-playing video with sound. Auto-rotating carousels.
- ❌ Bullet pyramids on slides. More than 5 bullets per slide.
- ❌ Numbers without units. Percentages without sign. Charts without
  axes.
- ❌ Red boxes around things. Highlight with a `ink` border or a
  side-rule, not red.
- ❌ Emoji in product marketing surfaces. (Social/Slack OK; landing
  page no.)
- ❌ Fake screenshots / mocked dashboards. If we can't show it for
  real, we don't show it.

---

## 18. Claude execution checklist

Before shipping any DeepSQL marketing deliverable, Claude should
confirm — out loud, in the response — that:

- [ ] The four jobs (or a clear subset) are legible in the deliverable.
- [ ] Palette is restricted to the tokens in §3. No new hues.
- [ ] Type is Inter (and JetBrains Mono for code). One family per role.
- [ ] At least one specific number, table name, or query appears
      somewhere — "operator-to-operator" credibility.
- [ ] Headlines are ≤ 9 words and declarative.
- [ ] No banned words (§2.4). No banned imagery (§8.2). No anti-patterns
      (§17).
- [ ] Contrast hits AA (AAA for hero text).
- [ ] Files named per §15 and saved into the workspace folder so
      Venkat can open them directly.
- [ ] For decks: fonts embedded, page numbers present, dark mode unless
      a reason to go light.
- [ ] For landing pages: a primary CTA + a secondary CTA, both verbs.

If a constraint here is intentionally being broken for a deliverable
(e.g., a co-marketing piece that has to wear another brand's colors),
say so explicitly in the response and propose the smallest possible
deviation.

---

## 19. Where to ask

- **Brand / positioning questions:** Venkat.
- **Product visual changes (in-app):** see `docs/root/CLAUDE.md` §UX.
- **MCP / Slack output formatting:** out of scope here — see
  `docs/root/MCP_PHASE1.md` and the Slack bot module when it lands.
- **This document:** update it when a rule is reversed, an asset
  convention changes, or a new surface (e.g., mobile app, video series)
  joins the brand. Treat changes the same way as a code PR — small,
  reviewed, dated.

_Last updated: 2026-05-12._
