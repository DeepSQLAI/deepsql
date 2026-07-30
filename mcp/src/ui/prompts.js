"use strict";

/**
 * Thin wrapper over @inquirer/prompts so commands import from one place.
 *
 * Why a wrapper?
 *   - Keeps the import surface stable if we ever swap libs.
 *   - Lets us short-circuit to the existing `prompt`/`promptPassword` helpers
 *     when stdin isn't a TTY (CI / piped input) — @inquirer requires a TTY
 *     and aborts otherwise, which is the wrong UX for scripted use.
 *   - Lazy-loads inquirer so plain CLI commands don't pay the load cost.
 */

const { prompt: tinyPrompt, promptPassword: tinyPromptPassword } = require("../auth/prompt");

let inquirer;
function loadInquirer() {
  if (!inquirer) inquirer = require("@inquirer/prompts");
  return inquirer;
}

async function input({ message, default: dflt, required = true, validate } = {}) {
  if (!process.stdin.isTTY) {
    const value = await tinyPrompt(`${message} `);
    if (required && !value) throw new Error(`${message} is required.`);
    return value;
  }
  return loadInquirer().input({ message, default: dflt, required, validate });
}

async function password({ message, mask = "*", validate } = {}) {
  if (!process.stdin.isTTY) {
    return tinyPromptPassword(`${message} `);
  }
  return loadInquirer().password({ message, mask, validate });
}

async function select({ message, choices, default: dflt } = {}) {
  if (!process.stdin.isTTY) {
    // Non-interactive: print the available choices and read one as a line.
    const labels = choices.map((c) => c.value).join(", ");
    const value = (await tinyPrompt(`${message} (${labels}): `)).trim();
    if (!choices.some((c) => c.value === value)) {
      throw new Error(`Invalid choice: ${value}. Pick one of: ${labels}`);
    }
    return value;
  }
  return loadInquirer().select({ message, choices, default: dflt });
}

async function confirm({ message, default: dflt = false } = {}) {
  if (!process.stdin.isTTY) {
    const raw = (await tinyPrompt(`${message} (y/N): `)).trim().toLowerCase();
    if (!raw) return dflt;
    return raw === "y" || raw === "yes";
  }
  return loadInquirer().confirm({ message, default: dflt });
}

module.exports = { input, password, select, confirm };
