"use strict";

/**
 * `deepsql explain` — deprecated alias for `deepsql analyze`.
 *
 * In 0.13.0 we consolidated SQL execution and plan analysis under two
 * canonical commands: `deepsql query` (executes anything) and
 * `deepsql analyze` (AI-enriched plan analysis, with optional ANALYZE).
 * `explain` was a thin read-only-locked subset of analyze, so it lives on
 * for one cycle as a forwarder while users migrate; it will be removed in
 * 0.14.0.
 */

const analyze = require("./analyze");

async function run(opts, io = {}) {
  const stderr = io.stderr || process.stderr;
  stderr.write(
    "[deepsql] `deepsql explain` is deprecated and will be removed in 0.14.0. "
    + "Use `deepsql analyze` (same behavior; add `--analyze` for EXPLAIN ANALYZE).\n",
  );
  return analyze.run(opts, io);
}

module.exports = { run };
