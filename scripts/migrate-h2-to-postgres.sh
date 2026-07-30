#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
H2_JAR="${H2_JAR:-$HOME/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar}"
PG_JAR="${PG_JAR:-$HOME/.m2/repository/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar}"

if [[ ! -f "$H2_JAR" ]]; then
  echo "H2 jar not found at $H2_JAR" >&2
  exit 1
fi

if [[ ! -f "$PG_JAR" ]]; then
  echo "PostgreSQL jar not found at $PG_JAR" >&2
  exit 1
fi

javac -cp "$H2_JAR:$PG_JAR" "$SCRIPT_DIR/H2ToPostgresMigrator.java"
java -cp "$H2_JAR:$PG_JAR:$SCRIPT_DIR" H2ToPostgresMigrator
