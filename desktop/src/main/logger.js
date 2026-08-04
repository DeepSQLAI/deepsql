'use strict';

/**
 * Tiny append-only logger. Everything lands in userData/logs/desktop.log so a
 * user can hand us one file when a tunnel misbehaves. Secrets never reach here:
 * callers pass already-redacted values (see profiles.toSafeProfile).
 */

const fs = require('node:fs');
const path = require('node:path');
const { app } = require('electron');

let stream = null;

function logFile() {
  return path.join(app.getPath('userData'), 'logs', 'desktop.log');
}

function ensureStream() {
  if (stream) return stream;
  const file = logFile();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  // Keep the log from growing without bound across months of use.
  try {
    if (fs.statSync(file).size > 5 * 1024 * 1024) {
      fs.renameSync(file, `${file}.1`);
    }
  } catch {
    // No existing log — nothing to rotate.
  }
  stream = fs.createWriteStream(file, { flags: 'a' });
  return stream;
}

function write(level, scope, message, extra) {
  const line = `${new Date().toISOString()} ${level.padEnd(5)} [${scope}] ${message}${
    extra === undefined ? '' : ` ${safeJson(extra)}`
  }\n`;
  try {
    ensureStream().write(line);
  } catch {
    // Logging must never take the app down.
  }
  if (level === 'ERROR') process.stderr.write(line);
  else process.stdout.write(line);
}

function safeJson(value) {
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

module.exports = {
  logFile,
  info: (scope, message, extra) => write('INFO', scope, message, extra),
  warn: (scope, message, extra) => write('WARN', scope, message, extra),
  error: (scope, message, extra) => write('ERROR', scope, message, extra),
};
