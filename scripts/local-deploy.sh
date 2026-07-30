#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESTART_FLAG=""

if [[ "${1:-}" == "--restart" ]]; then
  RESTART_FLAG="--restart"
  shift
fi

"$SCRIPT_DIR/start-all.sh" ${RESTART_FLAG:+"$RESTART_FLAG"}
"$SCRIPT_DIR/run-local-regression-suite.sh" "$@"
