#!/usr/bin/env bash
# No JDK on the host — run Maven goals in the same JDK image the backend builds with.
set -euo pipefail
exec docker run --rm \
  -v "$(git rev-parse --show-toplevel)/backend:/app" \
  -v "$HOME/.m2:/root/.m2" \
  -w /app maven:3-eclipse-temurin-25 \
  mvn -q -o test "$@"
