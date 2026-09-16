# Agent chat rendered agent output as HTML

*Found 2026-09-10 in a repository-wide security audit; fixed and verified in a browser
2026-09-16. Severity: high.*

## What was wrong

`AgentView` renders assistant replies through `dangerouslySetInnerHTML`, and the function
feeding it escaped nothing:

```js
function boldify(text) {
  return text
    .replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
}
```

Two sinks used it (`AgentView.jsx:906` and `:910`), so every character of an assistant
reply was parsed as markup. Confirmed by **executing the shipped function**, not by reading
it:

```
IN : <img src=x onerror=alert(document.cookie)>
OUT: <img src=x onerror=alert(document.cookie)>     byte for byte
```

`**a" onmouseover="alert(1)**` was worse than it looks: the `**` delimiters are consumed by
the substitution, so the attacker's quotes survived into the emitted tag and grew an
attribute on it.

## Why it mattered

**Stored, not reflected.** `agent_conversation.transcript` is a JSONB column replayed into
this renderer on load, so a payload fires again on every visit rather than once.

**The input is not trusted.** The agent echoes database content — table names, column
comments, sampled values. A real stored transcript in this install reads:

> No — `**DANGER**`. It rewrites the whole `` `t` `` table, takes an
> `**AccessExclusiveLock**` …

where `` `t` `` is a table name the agent read from the database. That is the same channel
an injected identifier arrives on: name a table `<img src=x onerror=…>` and the agent will
faithfully repeat it into an analyst's browser.

**Severity is capped, not removed.** Auth is an httpOnly cookie
(`AuthSessionService.java:256`), so the token itself cannot be read by injected script.
But `client.js` sends `withCredentials: true`, so the payload *acts as the reader* against
the API — running queries, reading results, and reaching admin endpoints if the reader is an
admin. Account takeover becomes session riding, which is better and still high.

One nuance worth recording so it is not rediscovered: a `<script>` tag inserted via
`innerHTML` **does not execute** (HTML spec). The live vector is an event-handler attribute
such as `onerror`. Both are escaped; the distinction matters when writing the regression
test, because asserting only on `<script>` would prove nothing.

## The fix

Escape first, then substitute:

```js
export function boldify(text, codeTag = PLAIN_CODE_TAG) {
  if (!text) return "";
  return escapeHtml(text)
    .replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, codeTag);
}
```

**Order is load-bearing.** Escaping *after* the substitutions would also escape the
`<strong>` and `<code>` tags this function emits, and the reader would see literal tag text
instead of formatting. That is the obvious thing to "simplify" later, so both halves are
pinned by tests.

**Why not swap in the safe renderer that already exists.** `AgentChat/AgentMarkdown.jsx`
uses `ReactMarkdown` + `remarkGfm`, which escapes HTML by default and deliberately omits
`rehype-raw`. It is the right long-term answer, but it is a *complete* renderer carrying its
own CSS module, a `DownloadableTable` and link handling, while `AgentView` has bespoke inline
styles for headings, bullets and fenced blocks. Swapping it in would make this a visual
redesign inside a security fix — harder to review, riskier to merge. Escaping at the source
is four lines and changes no pixels.

**Why not escape everything.** The agent leans on this formatting heavily, as the real
transcript above shows. A fix that rendered `**DANGER**` as literal asterisks would be
rejected by its users.

## The sibling sink

`Brain/AgentArtifacts.jsx:136` had the identical bug with a *styled* `<code>` tag. It is
currently unreachable — `features.js` has `AGENTS_ENABLED = false`, which removes `brain`
from the section map — but it is dead-code-adjacent rather than dead: the day that flag
flips, it is live.

Both renderers now share one escaping implementation, with only the code-tag markup passed
in. Duplicating the escape into both files is what lets one get fixed and the other missed —
the same drift the Java and JS SQL guards are kept in sync to avoid.

## Verification

| Step | Result |
|---|---|
| Tests against the real shipped `boldify` (RED) | **6 fail**, 5 pass — the 5 are the formatting cases, so they are not vacuous |
| Tests after the fix (GREEN) | 12 pass |
| `escapeHtml` removed (mutation) | **7 fail** — the tests guard the fix |
| Browser, payload through old code | `onerror` **executed**; 1 real `<img>` element in the DOM |
| Browser, payload through fixed code | `onerror` **did not execute**; **0** `<img>` elements; payload shown as visible text |
| `npm run build` | clean |
| `npm run lint` | 41 errors on main, **41 with this change** — unchanged baseline; 0 errors in the four files touched |
| Frontend tests | 22 pass, 0 fail |

The browser check is the one that matters. `imgTagsBefore: 1` shows Chromium parsed the
payload into a real element and fired its handler; `imgTagsAfter: 0` with the tag visible as
text shows the fixed path renders it as the data it is.

## Residual work

- There is **no `Content-Security-Policy` header**. `docker/nginx/default.conf` sets
  `X-Frame-Options`, `X-Content-Type-Options` and `Referrer-Policy` but no CSP, so nothing
  stands behind an escaping bug if another one is introduced. A `script-src` without
  `unsafe-inline` is the defence in depth this sink deserves; it is a deployment change with
  its own blast radius and belongs in its own PR.
- Migrating `AgentView` to `AgentMarkdown` remains the better long-term shape, as a
  deliberate UI change rather than a security fix.
