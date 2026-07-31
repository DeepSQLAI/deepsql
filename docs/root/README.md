# DBA Agent

AI-powered Database Performance Assistant with autonomous troubleshooting capabilities.

## Features

- **RAG-Powered SQL Generation** - Natural language to SQL queries
- **Connection-Scoped Rule Memory** - Learns deterministic SQL join/filter rules from feedback
- **Performance Monitoring** - Real-time database metrics and trends
- **Query Analysis** - EXPLAIN plan visualization and optimization suggestions
- **Lock Contention Detection** - Identify and resolve blocking queries
- **Index Recommendations** - AI-powered index suggestions
- **Playbook System** - Automated database health checks and troubleshooting
- **Configuration Tuner** - Database parameter optimization
- **Schema Visualization** - Interactive database schema explorer

## Quick Start

### Development Mode (with Auth Bypass)

```bash
# Backend (uses application.properties with auth disabled)
cd backend
./mvnw spring-boot:run

# Frontend (dev mode with admin/admin bypass)
npm install
npm run dev
```

Visit http://localhost:3000 and login with:
- Username: `admin`
- Password: `admin`

### Production Mode (with Real Auth)

```bash
# Backend (uses application-prod.properties with auth enabled)
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod

# Frontend (production build - no bypass)
npm run build:production
```

**Authentication Configuration:**
- **Development**: Auth is bypassed (`security.auth.enabled=false`)
  - Frontend accepts `admin/admin` credentials
  - Backend skips JWT validation
- **Production**: Real authentication required (`security.auth.enabled=true`)
  - Users must register and login with valid credentials
  - Full JWT token validation

## Documentation

All documentation is in the `docs/` folder (see the [Documentation Index](../README.md)):

- [Setup Guide](../SETUP.md) - Complete installation instructions
- [Quick Start](../QUICKSTART.md) - Get started quickly
- [API Client Usage](../API_CLIENT_USAGE.md) - Frontend API integration guide
- [RAG Setup](../RAG_SETUP.md) - Configure RAG for SQL generation
- [Chat Memory + Guardrails](../CHAT_MEMORY_AND_GUARDRAILS.md) - Architecture and operational flow

## Tech Stack

**Backend:**
- Spring Boot 3.x
- MySQL 8.0+
- OpenAI API (for RAG and analysis)

**Frontend:**
- Next.js 16.1 (React 19)
- Axios for API calls
- Recharts for visualization
- Monaco Editor for SQL editing

## Architecture

- **Centralized API Client** - All API calls go through `src/lib/api/client.js`
- **Component-based UI** - Modular tab-based interface
- **Playbook System** - Autonomous database monitoring and remediation
- **Safe Tool Registry** - Read-only database inspection tools

## Development

```bash
# Install dependencies
npm install

# Run dev server
npm run dev

# Build for production
npm run build

# Start production server
npm start
```

## License

Apache License 2.0 — see [LICENSE](../../LICENSE)
