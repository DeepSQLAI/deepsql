# DBA Agent - Local Setup Guide

This guide will help you run the DBA Agent project locally.

## Prerequisites

### Frontend (Next.js)
- Node.js 18+ and npm
- Already installed: ✅ Node.js v24.10.0, npm v11.6.0

### Backend (Java Spring Boot)
- Java 17 or higher
- Maven 3.6+ (or use Maven wrapper)

## Setup Instructions

### 1. Install Frontend Dependencies

```bash
cd /Users/venkat/Documents/GitHub/dbaagent
npm install
```

This will install:
- axios (API client)
- react-force-graph-2d (visualization)
- All other Next.js dependencies

### 2. Setup Backend (Java)

#### Start Vault Database (PostgreSQL)
```bash
cd /Users/venkat/Documents/GitHub/dbaagent
docker compose up -d postgres
```

Defaults used by the backend:
- DB: `dba_agent`
- User: `postgres`
- Password: (empty)

#### Option A: Using Maven (if installed)
```bash
cd backend
mvn clean install
mvn spring-boot:run
```

#### Option B: Using Maven Wrapper (recommended)
```bash
cd backend
# First time: Make wrapper executable (macOS/Linux)
chmod +x mvnw
./mvnw clean install
./mvnw spring-boot:run
```

#### Option C: Using IDE
- Open the `backend` folder in IntelliJ IDEA or Eclipse
- Import as Maven project
- Run `DbaAgentApplication.java`

### 3. Configure Environment Variables

Create a `.env.local` file in the root directory:

```bash
NEXT_PUBLIC_API_URL=http://localhost:8080
```

For the backend, set encryption key (optional, defaults to a test key):

```bash
export ENCRYPTION_KEY=your-secure-encryption-key-here
```

### 4. Start the Services

#### Terminal 1 - Backend (Java)
```bash
cd backend
./mvnw spring-boot:run
# Or if Maven is installed:
mvn spring-boot:run
```

Backend will start on: `http://localhost:8080`

#### Terminal 2 - Frontend (Next.js)
```bash
cd /Users/venkat/Documents/GitHub/dbaagent
npm run dev
```

Frontend will start on: `http://localhost:3000`

## Verify Installation

1. **Backend Health Check**: Open `http://localhost:8080/api/connections` in your browser
   - Should return an empty array `[]` (no connections yet)

2. **Frontend**: Open `http://localhost:3000`
   - Should see the DBA Agent interface

## Usage

1. Click the Settings icon (⚙️) in the left panel
2. Select MySQL or PostgreSQL
3. Enter your database connection details:
   - Connection Name (e.g., "My Production DB")
   - Host (e.g., localhost)
   - Port (3306 for MySQL, 5432 for PostgreSQL)
   - Database Name
   - Username
   - Password
   - SSL (if needed)
4. Click "Test Connection" to verify
5. Click "Save Configuration" to save and trigger automatic schema scan
6. View ER diagram and dependency graph in the Preview tab
7. Check DBA stats in the Analytics tab

## Troubleshooting

### Backend Issues

**Java not found:**
- Install Java 17+: `brew install openjdk@17` (macOS)
- Or download from: https://adoptium.net/

**Maven not found:**
- Install Maven: `brew install maven` (macOS)
- Or use the Maven wrapper: `./mvnw` (included in project)

**Port 8080 already in use:**
- Change port in `backend/src/main/resources/application.properties`:
  ```
  server.port=8081
  ```
- Update frontend `.env.local`: `NEXT_PUBLIC_API_URL=http://localhost:8081`

### Frontend Issues

**npm install fails:**
- Try: `npm install --legacy-peer-deps`
- Or: `npm cache clean --force` then `npm install`

**Port 3000 already in use:**
- Next.js will automatically use the next available port (3001, 3002, etc.)

**API connection errors:**
- Ensure backend is running on port 8080
- Check `NEXT_PUBLIC_API_URL` in `.env.local`
- Check browser console for CORS errors

**Postgres.app trust error:**
- If you see `Postgres.app failed to verify "trust" authentication`, open Postgres.app settings and approve the permission prompt.

## Project Structure

```
dbaagent/
├── backend/                 # Java Spring Boot backend
│   ├── src/main/java/       # Java source code
│   ├── pom.xml              # Maven dependencies
│   └── mvnw                 # Maven wrapper
├── src/                     # Next.js frontend
│   ├── app/                 # Next.js app router
│   ├── components/          # React components
│   └── lib/api/             # API client
└── package.json             # Node.js dependencies
```

## Next Steps

After setup:
1. Connect to your first database
2. Explore the schema visualizations
3. Review DBA performance stats
4. Test SQL queries in the SQL Runner tab

For questions or issues, check the code comments or review the implementation plan.
