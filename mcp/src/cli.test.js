"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { parseArgs, buildOpts, main, renderRootHelp, renderCommandHelp, COMMAND_HELP } = require("./cli");

function captureStreams() {
  let out = "";
  let err = "";
  return {
    stdout: { write: (s) => { out += s; }, isTTY: false },
    stderr: { write: (s) => { err += s; }, isTTY: false },
    out: () => out,
    err: () => err,
  };
}

test("parseArgs collects positional args", () => {
  const { positional, flags } = parseArgs(["how", "many", "rows"]);
  assert.deepEqual(positional, ["how", "many", "rows"]);
  assert.deepEqual(flags, {});
});

test("parseArgs handles --flag value and --flag=value", () => {
  const a = parseArgs(["--url", "http://x", "--connection=c1"]);
  assert.equal(a.flags.url, "http://x");
  assert.equal(a.flags.connection, "c1");
});

test("parseArgs camelCases dashed flags", () => {
  const { flags } = parseArgs(["--no-browser", "--timeout-seconds", "30"]);
  assert.equal(flags.noBrowser, true);
  assert.equal(flags.timeoutSeconds, "30");
});

test("parseArgs treats trailing -- as positional separator", () => {
  const { positional } = parseArgs(["query", "--", "SELECT 1"]);
  assert.deepEqual(positional, ["query", "SELECT 1"]);
});

test("buildOpts maps known flags", () => {
  const parsed = parseArgs(["--connection", "c1", "--json", "--limit", "50", "SELECT 1"]);
  const opts = buildOpts(parsed);
  assert.equal(opts.connection, "c1");
  assert.equal(opts.json, true);
  assert.equal(opts.limit, "50");
  assert.deepEqual(opts.positional, ["SELECT 1"]);
});

test("renderRootHelp lists every command and marks subcommand-bearing ones with *", () => {
  const text = renderRootHelp(false);
  assert.match(text, /🐬 DeepSQL/);
  assert.match(text, /Hint: commands suffixed with \* have subcommands/);
  // Leaf command — no star.
  assert.match(text, /\n\s+login\s+ /);
  // Parent commands — must carry a star.
  for (const name of ["config", "connections", "digest", "users", "access", "permissions", "slow-queries"]) {
    assert.match(text, new RegExp(`\\n\\s+${name} \\*`), `expected "${name} *" in root help`);
  }
});

test("renderCommandHelp emits subcommands and options for parent commands", () => {
  const text = renderCommandHelp("connections", false);
  assert.match(text, /Usage: deepsql connections/);
  assert.match(text, /Subcommands:/);
  assert.match(text, /Options:/);
  assert.match(text, /list \[--json\]/);
});

test("every command in the catalog has a COMMAND_HELP entry", () => {
  // Sourced from the dispatcher map so the lists stay in lockstep.
  const { main: _main } = require("./cli");
  void _main;
  const expected = [
    "agent",
    "login","logout","whoami","config","mcp","connections","query","analyze","schema",
    "digest","brain-context","brain","business-rules","relationships","anti-patterns","indexes",
    "users","access","permissions","slow-queries","setup",
  ];
  for (const name of expected) {
    assert.ok(COMMAND_HELP[name], `missing COMMAND_HELP for ${name}`);
    assert.ok(COMMAND_HELP[name].usage, `missing usage for ${name}`);
  }
});

test("main prints root help on no args and on --help", async () => {
  for (const argv of [[], ["--help"], ["-h"]]) {
    const io = captureStreams();
    const code = await main(argv, io);
    assert.equal(code, 0);
    assert.match(io.out(), /Usage: deepsql \[options\] \[command\]/);
  }
});

test("main prints per-command help when --help follows the command", async () => {
  const io = captureStreams();
  const code = await main(["connections", "--help"], io);
  assert.equal(code, 0);
  assert.match(io.out(), /Usage: deepsql connections/);
  assert.match(io.out(), /Subcommands:/);
});

test("main rejects unknown commands and shows the root help", async () => {
  const io = captureStreams();
  const code = await main(["nope-not-real"], io);
  assert.equal(code, 2);
  assert.match(io.err(), /Unknown command: nope-not-real/);
  assert.match(io.err(), /Usage: deepsql/);
});

test("main preserves a non-zero command return code", async () => {
  const io = captureStreams();
  const code = await main(["connections", "current", "--url", "http://x", "--token", "t"], io);
  assert.equal(code, 1);
  assert.match(io.err(), /No active connection set/);
});

test("--no-color suppresses ANSI escapes in help output", async () => {
  const io = captureStreams();
  // Force a TTY so the only thing turning color off is --no-color itself.
  io.stdout.isTTY = true;
  await main(["--help", "--no-color"], io);
  // No ESC bytes anywhere.
  assert.equal(/\x1b\[/.test(io.out()), false, "help output should be plain when --no-color is set");
});

// ─── help/dispatch drift guard ─────────────────────────────────────────────
//
// Every command-module SUBCOMMANDS map must be reflected in COMMAND_HELP so
// `deepsql <command> -h` actually documents what `deepsql <command> <sub>`
// will dispatch. A previous regression shipped four new slow-query
// subcommands that worked but weren't listed in -h — users assumed the
// install was stale. This test catches that class of bug at PR time.
//
// To extend: add a `{ command, modulePath }` entry. The module must export
// a `SUBCOMMANDS` object whose keys are the dispatchable subcommand names.
const HELP_DRIFT_TARGETS = [
  { command: "slow-queries", modulePath: "./commands/slow-queries" },
  { command: "brain", modulePath: "./commands/brain" },
  // `config` dispatched from a bare switch with no SUBCOMMANDS export, so it sat
  // outside this guard entirely and its help could drift unnoticed. It now
  // exports the map like its siblings.
  { command: "config", modulePath: "./commands/config" },
];

for (const { command, modulePath } of HELP_DRIFT_TARGETS) {
  test(`COMMAND_HELP["${command}"] documents every dispatchable subcommand`, () => {
    const mod = require(modulePath);
    assert.ok(mod.SUBCOMMANDS, `${modulePath} must export SUBCOMMANDS for the drift guard`);
    const help = COMMAND_HELP[command];
    assert.ok(help, `COMMAND_HELP["${command}"] is missing`);
    assert.ok(Array.isArray(help.subcommands), `COMMAND_HELP["${command}"].subcommands must be an array`);

    const dispatchable = Object.keys(mod.SUBCOMMANDS).sort();
    // Each help row is [usage-string, description]; the first whitespace-
    // separated token of usage-string is the subcommand name.
    const documented = help.subcommands
      .map((row) => String(row[0]).trim().split(/\s+/)[0])
      .sort();
    const missing = dispatchable.filter((s) => !documented.includes(s));
    const stale = documented.filter((s) => !dispatchable.includes(s));

    assert.deepEqual(
      { missing, stale },
      { missing: [], stale: [] },
      `\`deepsql ${command} -h\` is out of sync with src/commands/${command}.js:\n`
        + `  missing from help (callable but undocumented): ${missing.join(", ") || "none"}\n`
        + `  stale in help   (documented but not callable): ${stale.join(", ") || "none"}\n`
        + `Fix: update COMMAND_HELP["${command}"].subcommands in src/cli.js.`,
    );
  });
}
