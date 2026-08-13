# Changelog

All notable changes to DeepSQL are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for product tags (`vMAJOR.MINOR.PATCH`).

## [1.0.0] — 2026-08-13

First public OSS release.

### Added

- Self-hosted DeepSQL stack: Spring Boot backend, React UI, DeepSQL Agent, MCP/CLI (`@deepsql/mcp`).
- Brain initialization with multi-schema discovery, coverage gates, and terminal `NEEDS_ATTENTION` handling.
- Agent tab SSO bridge with per-user MCP tokens, token-file rotation, and fail-loud provisioning.
- Dashboard artifact generation (HTML + sandboxed `deepsql.query` bridge).
- BYO LLM via OpenAI-compatible providers (`LlmProviderRegistry` / `LlmConfigResolver`).
- Official release tooling: `scripts/release/build-artifacts.sh` and tag-driven GitHub Release workflow.

### Security

- Session kill endpoints reject non-integer PIDs (SQL injection class closed).
- Dangerous controllers enforce connection ACL; Hermes/agent provisioner bind loopback.
- Compose Valkey password + Actuator limited to health for anonymous callers.
- JWT / session auth fail-closed when secrets are missing.

### Known limitations

- Residual high-severity items tracked in `docs/oss-ux/OSS_SECURITY_REVIEW.md` (IDOR sweep, SET preamble allowlist, SSRF hardening, share-password defaults) are deferred past this cut.
- Primary distribution path remains `docker compose up --build` (no pre-built container registry in this release).

[1.0.0]: https://github.com/DeepSQLAI/deepsql/releases/tag/v1.0.0
