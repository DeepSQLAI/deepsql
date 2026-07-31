# DBA Agent Services

This document describes all services required for the DBA Agent application and how to manage them.

## Services Overview

The DBA Agent application requires the following services:

| Service | Port | Purpose | Technology |
|---------|------|---------|------------|
| **PostgreSQL** | 5432 | Backend database (stores connection configs, users, etc.) | Docker/PostgreSQL 18.1 |
| **Valkey/Redis** | 6379 | Cache layer | Docker/Valkey 9.0.1 |
| **Backend** | 8080 | Spring Boot API server | Java/Spring Boot |
| **Frontend** | 3000 | React/Vite web application | Node.js/Vite |

## Quick Start Scripts

### Check Service Status
```bash
./scripts/check-services.sh
```
Checks if all services are running and displays their status.

### Start All Services
```bash
./scripts/start-all.sh
```
Starts all services in the correct order:
1. PostgreSQL & Valkey (via Docker Compose)
2. Backend (Spring Boot)
3. Frontend (Vite/React)

### Restart All Services
```bash
./scripts/start-all.sh --restart
```
Stops and restarts all services.

### Stop All Services
```bash
./scripts/stop-all.sh
```
Stops all running services.

## Manual Service Management

### PostgreSQL

**Using Docker Compose (Recommended):**
```bash
# Start PostgreSQL
docker compose up -d postgres

# Check status
docker compose ps

# View logs
docker compose logs postgres

# Stop PostgreSQL
docker compose stop postgres
```

**Using Local PostgreSQL:**
```bash
# Start PostgreSQL service (macOS)
brew services start postgresql@18

# Create database
createdb dba_agent

# Check if running
pg_isready -h localhost -p 5432

# Connect to database
psql -h localhost -U postgres -d dba_agent
```

**Connection Details:**
- Host: `localhost`
- Port: `5432`
- Database: `dba_agent`
- Username: `postgres`
- Password: `postgres` (default)

### Valkey/Redis

**Using Docker Compose:**
```bash
# Start Valkey
docker compose up -d valkey

# Check status
docker compose ps valkey

# Connect to Valkey
docker compose exec valkey valkey-cli
```

**Using Local Redis:**
```bash
# Start Redis (macOS)
brew services start redis

# Check if running
redis-cli ping
```

**Connection Details:**
- Host: `localhost`
- Port: `6379`

### Backend (Spring Boot)

**Start Backend:**
```bash
cd backend
./mvnw spring-boot:run
# OR
mvn spring-boot:run
```

**Backend runs on:** `http://localhost:8080/api`

**Health Check:**
```bash
curl http://localhost:8080/api/actuator/health
```

**Logs:**
- Console output (if run directly)
- `backend-dev.log` (if run in background)

### Frontend (Vite/React)

**Start Frontend:**
```bash
npm install  # First time only
npm run dev
```

**Frontend runs on:** `http://localhost:3000`

**Logs:**
- Console output (if run directly)
- `frontend-dev.log` (if run in background)

## Service Dependencies

```
Frontend (3000)
    └──> Backend (8080)
            ├──> PostgreSQL (5432)
            └──> Valkey/Redis (6379)
```

**Startup Order:**
1. PostgreSQL & Valkey (can start in parallel)
2. Backend (waits for PostgreSQL)
3. Frontend (waits for Backend)

## Troubleshooting

### Check if Ports are in Use
```bash
# Check specific port
lsof -i :8080
lsof -i :3000
lsof -i :5432
lsof -i :6379

# Kill process on port (if needed)
lsof -ti:8080 | xargs kill -9
```

### Check Service Logs

**Backend:**
```bash
tail -f backend-dev.log
```

**Frontend:**
```bash
tail -f frontend-dev.log
```

**Docker Services:**
```bash
docker compose logs -f postgres
docker compose logs -f valkey
```

### Common Issues

1. **Port Already in Use**
   - Use `./scripts/start-all.sh --restart` to restart services
   - Or manually kill the process: `lsof -ti:PORT | xargs kill -9`

2. **PostgreSQL Connection Failed**
   - Ensure PostgreSQL is running: `pg_isready -h localhost -p 5432`
   - Check Docker: `docker compose ps`
   - Verify database exists: `psql -h localhost -U postgres -l | grep dba_agent`

3. **Backend Won't Start**
   - Check Java version: `java -version` (needs Java 17+)
   - Check Maven: `mvn -version`
   - Check logs: `tail -f backend-dev.log`

4. **Frontend Won't Start**
   - Install dependencies: `npm install`
   - Check Node version: `node -v` (needs Node 18+)
   - Check logs: `tail -f frontend-dev.log`

## Environment Variables

### Backend
- `DB_URL` - Database connection URL (default: `jdbc:postgresql://localhost:5432/dba_agent`)
- `DB_USERNAME` - Database username (default: `postgres`)
- `DB_PASSWORD` - Database password (default: `postgres`)

### Frontend
- `VITE_API_URL` - Backend API URL (default: `http://localhost:8080`)

## Production Deployment

For production, services should be:
- Running as system services (systemd, launchd, etc.)
- Behind a reverse proxy (nginx, Apache)
- Using environment-specific configuration files
- Monitored with health checks and logging

See [SELF_HOST_GUIDE.md](./root/SELF_HOST_GUIDE.md) for the Docker Compose stack:
host sizing, the production environment reference, TLS and reverse-proxy setup,
health checks, upgrades, and backup and restore.
