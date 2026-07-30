#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG_JAR="${PG_JAR:-$HOME/.m2/repository/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar}"

if [[ ! -f "$PG_JAR" ]]; then
  echo "PostgreSQL jar not found at $PG_JAR" >&2
  exit 1
fi

javac -cp "$PG_JAR" "$SCRIPT_DIR/ResetPostgres.java"
java -cp "$PG_JAR:$SCRIPT_DIR" ResetPostgres
