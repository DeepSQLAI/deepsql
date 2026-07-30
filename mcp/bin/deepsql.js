#!/usr/bin/env node
"use strict";

const { main } = require("../src/cli");

main()
  .then((code) => {
    if (typeof code === "number") process.exit(code);
  })
  .catch((err) => {
    process.stderr.write(`Fatal: ${err.message}\n`);
    process.exit(1);
  });
