"use strict";

/**
 * Resolves the real user's home directory, even when the CLI is run via sudo.
 *
 * Priority: HOME env var → SUDO_USER /etc/passwd lookup → os.homedir().
 * The "/root" guard on HOME handles `sudo -H` (which sets HOME=/root) while
 * still respecting `sudo -E` (which preserves the original HOME).
 */

const fs = require("node:fs");
const os = require("node:os");

function userHome() {
  if (process.env.HOME && process.env.HOME !== "/root") return process.env.HOME;
  if (process.env.SUDO_USER) {
    try {
      const passwd = fs.readFileSync("/etc/passwd", "utf8");
      const line = passwd.split("\n").find(l => l.startsWith(process.env.SUDO_USER + ":"));
      if (line) {
        const home = line.split(":")[5];
        if (home) return home;
      }
    } catch (_) { /* fall through */ }
  }
  return os.homedir();
}

module.exports = { userHome };
