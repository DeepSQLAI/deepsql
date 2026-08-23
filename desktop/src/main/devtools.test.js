'use strict';

/**
 * Drift guard for the DevTools kill switch.
 *
 * These are source assertions, not behavioural ones, because the modules they
 * cover require('electron') at load time and cannot be imported outside an
 * Electron process. That is a real limit — it catches a webPreferences block
 * added without `devTools`, not a runtime regression inside Electron itself.
 * The behavioural check is the manual one in README.md ("Verifying the block").
 *
 * The failure this exists to prevent is silent: a new window added without
 * `devTools` inherits Chromium's default of *enabled*, so the app keeps working
 * perfectly and simply hands DevTools back to users on that window.
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const MAIN_DIR = __dirname;

/** Every non-test .js file under src/main. */
function sourceFiles(dir = MAIN_DIR, found = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) sourceFiles(full, found);
    else if (entry.name.endsWith('.js') && !entry.name.endsWith('.test.js')) found.push(full);
  }
  return found;
}

/**
 * Blank out comments while preserving line numbering, so prose that merely
 * mentions openDevTools() is not mistaken for a call site.
 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, prefix) => prefix + ' '.repeat(m.length - prefix.length));
}

/**
 * Extract the `{ ... }` object literal that follows each `webPreferences:` key,
 * matching braces so nested objects do not truncate the block.
 */
function webPreferenceBlocks(source) {
  const blocks = [];
  const key = /webPreferences\s*:\s*\{/g;
  let match;
  while ((match = key.exec(source)) !== null) {
    let depth = 1;
    let i = match.index + match[0].length;
    while (i < source.length && depth > 0) {
      if (source[i] === '{') depth += 1;
      else if (source[i] === '}') depth -= 1;
      i += 1;
    }
    blocks.push({
      body: source.slice(match.index, i),
      line: source.slice(0, match.index).split('\n').length,
    });
  }
  return blocks;
}

test('every webPreferences block sets devTools', () => {
  const offenders = [];
  for (const file of sourceFiles()) {
    const source = fs.readFileSync(file, 'utf8');
    for (const block of webPreferenceBlocks(source)) {
      if (!/\bdevTools\s*:/.test(block.body)) {
        offenders.push(`${path.relative(MAIN_DIR, file)}:${block.line}`);
      }
    }
  }
  assert.deepStrictEqual(
    offenders,
    [],
    `webPreferences without devTools (Chromium defaults these to enabled): ${offenders.join(', ')}`,
  );
});

test('devTools is gated on DEVTOOLS_ENABLED, never on IS_DEV', () => {
  for (const file of sourceFiles()) {
    const source = fs.readFileSync(file, 'utf8');
    for (const block of webPreferenceBlocks(source)) {
      const setting = block.body.match(/\bdevTools\s*:\s*([^,\n}]+)/);
      assert.ok(setting, `no devTools value parsed in ${path.relative(MAIN_DIR, file)}`);
      assert.strictEqual(
        setting[1].trim(),
        'DEVTOOLS_ENABLED',
        `${path.relative(MAIN_DIR, file)}:${block.line} must use DEVTOOLS_ENABLED — ` +
          'IS_DEV is true whenever DEEPSQL_DESKTOP_DEV=1, which any user can set',
      );
    }
  }
});

test('DEVTOOLS_ENABLED does not read the environment', () => {
  const config = fs.readFileSync(path.join(MAIN_DIR, 'config.js'), 'utf8');
  const declaration = config.match(/const DEVTOOLS_ENABLED\s*=\s*([^;]+);/);
  assert.ok(declaration, 'DEVTOOLS_ENABLED declaration not found in config.js');
  const value = declaration[1].trim();

  assert.strictEqual(
    value,
    '!app.isPackaged',
    'DEVTOOLS_ENABLED must derive from app.isPackaged alone. Deriving it from ' +
      'process.env or IS_DEV lets a user re-enable DevTools on the shipped app.',
  );
});

test('no code path opens DevTools without checking DEVTOOLS_ENABLED', () => {
  const offenders = [];
  for (const file of sourceFiles()) {
    const lines = stripComments(fs.readFileSync(file, 'utf8')).split('\n');
    lines.forEach((line, index) => {
      if (!/\b(openDevTools|toggleDevTools)\s*\(/.test(line)) return;
      // Heuristic: the guard sits on this line or opens a block shortly above
      // it (the menu template spreads a conditional array several lines up).
      const context = lines.slice(Math.max(0, index - 10), index + 1).join('\n');
      if (!context.includes('DEVTOOLS_ENABLED')) {
        offenders.push(`${path.relative(MAIN_DIR, file)}:${index + 1}`);
      }
    });
  }
  assert.deepStrictEqual(
    offenders,
    [],
    `unguarded DevTools call: ${offenders.join(', ')}`,
  );
});
