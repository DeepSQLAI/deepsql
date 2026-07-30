"use strict";

/**
 * Spawn the user's $EDITOR on a tempfile and return the edited contents.
 *
 * Resolution order: VISUAL → EDITOR → vi (POSIX) / notepad (Windows).
 * Tempfile is created mode 0600 in os.tmpdir(), removed on exit (success or
 * cancel), and uses a randomised suffix so concurrent edits don't clash.
 */

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { spawn } = require("node:child_process");

function pickEditor() {
  if (process.env.VISUAL) return process.env.VISUAL;
  if (process.env.EDITOR) return process.env.EDITOR;
  return process.platform === "win32" ? "notepad" : "vi";
}

async function editText(initial, { suffix = ".txt", header = null } = {}) {
  const random = crypto.randomBytes(6).toString("hex");
  const tmp = path.join(os.tmpdir(), `deepsql-${random}${suffix}`);
  let body = initial == null ? "" : String(initial);
  if (header) body = `${header}\n\n${body}`;

  fs.writeFileSync(tmp, body, { mode: 0o600 });
  const before = fs.readFileSync(tmp, "utf8");

  try {
    await new Promise((resolve, reject) => {
      const editor = pickEditor();
      const child = spawn(editor, [tmp], { stdio: "inherit" });
      child.on("exit", (code) => {
        if (code === 0) resolve();
        else reject(new Error(`Editor exited with code ${code}`));
      });
      child.on("error", reject);
    });

    const after = fs.readFileSync(tmp, "utf8");
    return {
      content: stripHeader(after, header),
      changed: stripHeader(after, header) !== stripHeader(before, header),
    };
  } finally {
    try {
      fs.unlinkSync(tmp);
    } catch {}
  }
}

function stripHeader(text, header) {
  if (!header) return text;
  const headerBlock = `${header}\n\n`;
  return text.startsWith(headerBlock) ? text.slice(headerBlock.length) : text;
}

module.exports = { editText, pickEditor };
