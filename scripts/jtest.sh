#!/usr/bin/env bash
# No JDK on the host — run Maven goals in the same JDK image the backend builds with.
set -euo pipefail
# Docker socket mount + TESTCONTAINERS_HOST_OVERRIDE let Testcontainers (used by
# PostgresMigrationRiskVerificationTest) start a sibling Postgres container and reach its
# mapped port from inside this maven container. -o (offline) is dropped because it blocks
# resolving new test dependencies against the local repo the first time they're added.
exec docker run --rm \
  -v "$(git rev-parse --show-toplevel)/backend:/app" \
  -v "$HOME/.m2:/root/.m2" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -w /app maven:3-eclipse-temurin-25 \
  mvn -q test "$@"
