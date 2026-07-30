# DBA Agent Documentation

This folder contains all technical documentation for the DBA Agent project.

## Setup & Getting Started

- **[SETUP.md](./SETUP.md)** - Complete installation and configuration guide
- **[QUICKSTART.md](./QUICKSTART.md)** - Quick start guide to get up and running
- **[RAG_SETUP.md](./RAG_SETUP.md)** - Configure RAG for natural language SQL generation
- **[root/SELF_HOST_GUIDE.md](./root/SELF_HOST_GUIDE.md)** - Customer-facing Docker self-host guide and packaging flow

## Architecture & Implementation

- **[API_CLIENT_USAGE.md](./API_CLIENT_USAGE.md)** - Centralized API client documentation
- **[CHAT_MEMORY_AND_GUARDRAILS.md](./CHAT_MEMORY_AND_GUARDRAILS.md)** - How chat memory, RAG, and feedback-driven SQL guardrails work together
- **[TOOL_AWARE_CHAT_MEMORY.md](./TOOL_AWARE_CHAT_MEMORY.md)** - Current Spring AI JDBC chat memory behavior and troubleshooting
- **[QUICK_WINS_IMPLEMENTATION_GUIDE.md](./QUICK_WINS_IMPLEMENTATION_GUIDE.md)** - Feature implementation guide
- **[FEATURE_GAP_ANALYSIS.md](./FEATURE_GAP_ANALYSIS.md)** - Competitive analysis and feature planning
- **[OPTD_SIDECAR.md](./OPTD_SIDECAR.md)** - Query optimization sidecar integration

## Feature Documentation

### Database Advisor
- **[DATABASE_ADVISOR.md](./DATABASE_ADVISOR.md)** - AI-powered database optimization advisor
- **[DATABASE_ADVISOR_UI.md](./DATABASE_ADVISOR_UI.md)** - Advisor UI implementation details

### Query Analysis
- **[EXPLAIN_HISTORY_FEATURE.md](./EXPLAIN_HISTORY_FEATURE.md)** - EXPLAIN plan history tracking
- **[DEBUG_EXPLAIN_TAB.md](./DEBUG_EXPLAIN_TAB.md)** - Debugging guide for EXPLAIN tab

### Performance Dashboard
- **[PERFORMANCE_UI_IMPLEMENTATION.md](./PERFORMANCE_UI_IMPLEMENTATION.md)** - Performance dashboard implementation
- **[PERFORMANCE_UI_BUGFIXES.md](./PERFORMANCE_UI_BUGFIXES.md)** - Performance UI bug fixes and improvements

## OPTD Sidecar Quickstart

1. Start the sidecar:
   ```
   cd /Users/geekypunk/sasank/stayflexi/optd
   cargo run -p optd-sidecar
   ```
2. Start the backend:
   ```
   cd /Users/geekypunk/sasank/stayflexi/dba-agent/backend
   mvn -q -DskipTests spring-boot:run
   ```
3. Confirm health:
   ```
   curl -s http://localhost:8088/health
   ```

## OPTD Sidecar Troubleshooting

- **Sidecar returns 400**: query parse failed or schema is missing fields.
  - Check `/tmp/optd-sidecar.log` for parse errors.
  - Verify schema metadata is present and MySQL normalization is applied.
- **No plan signature/cost**: optd not reached or request failed.
  - Check backend logs `/tmp/dba-agent.log`.
  - Verify `optd.enabled=true` and `optd.sidecar.base-url` in `application.properties`.

## Troubleshooting Guides

- **[COMPILE_ERRORS_FIX.md](./COMPILE_ERRORS_FIX.md)** - Common compilation errors and fixes
- **[FINAL_FIX.md](./FINAL_FIX.md)** - Final bug fixes and patches
- **[QUICK_FIX.md](./QUICK_FIX.md)** - Quick fixes for common issues
- **[README_FIX.md](./README_FIX.md)** - README-related fixes
- **[RUN_THIS_NOW.md](./RUN_THIS_NOW.md)** - Urgent fixes and patches
- **[URGENT_FIX.md](./URGENT_FIX.md)** - Critical bug fixes

## Navigation Tips

- All feature guides include implementation details and code examples
- Setup guides assume a clean installation
- Troubleshooting guides are organized by severity (URGENT, QUICK, etc.)

## Contributing

When adding new documentation:
1. Use clear, descriptive filenames in UPPERCASE with underscores
2. Include a brief summary at the top of each document
3. Update this README.md index
4. Link to related documents where appropriate
