"use strict";

/**
 * Tiny interactive prompt helpers — no dependency on a TTY library.
 *
 * For passwords we mute the terminal echo so the value does not show on screen
 * or get captured by ttyrec / screen-recording. If stdin is not a TTY (piped
 * input), we read a single line as-is.
 */

const readline = require("node:readline");

function prompt(question) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stderr });
    rl.question(question, (answer) => {
      rl.close();
      resolve(answer.trim());
    });
  });
}

function promptPassword(question) {
  if (!process.stdin.isTTY) {
    return readSingleLineFromStdin();
  }
  return new Promise((resolve, reject) => {
    process.stderr.write(question);
    const stdin = process.stdin;
    const previousRaw = stdin.isRaw;
    stdin.setRawMode(true);
    stdin.resume();
    stdin.setEncoding("utf8");

    const ETX = "\u0003"; // Ctrl-C
    const EOT = "\u0004"; // Ctrl-D
    const BS  = "\u0008"; // ASCII backspace
    const DEL = "\u007f"; // most modern terminals send this on backspace

    let buffer = "";
    const onData = (char) => {
      switch (char) {
        case "\r":
        case "\n":
        case EOT:
          finish();
          return;
        case ETX:
          cleanup();
          process.stderr.write("\n");
          reject(new Error("Cancelled"));
          return;
        case BS:
        case DEL:
          buffer = buffer.slice(0, -1);
          return;
        default:
          // Accept printable chars only; ignore other ANSI control sequences.
          if (char.charCodeAt(0) >= 32) buffer += char;
      }
    };

    const cleanup = () => {
      stdin.removeListener("data", onData);
      try {
        stdin.setRawMode(previousRaw);
      } catch {}
      stdin.pause();
    };

    const finish = () => {
      cleanup();
      process.stderr.write("\n");
      resolve(buffer);
    };

    stdin.on("data", onData);
  });
}

function readSingleLineFromStdin() {
  return new Promise((resolve, reject) => {
    let buffer = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => {
      buffer += chunk;
    });
    process.stdin.on("end", () => {
      // Strip a single trailing newline if the caller did echo \$PWD | …
      resolve(buffer.replace(/\r?\n$/, ""));
    });
    process.stdin.on("error", reject);
  });
}

module.exports = { prompt, promptPassword, readSingleLineFromStdin };
