#!/bin/bash

# Start all DBA Agent services
# Usage: ./scripts/start-all.sh [--restart]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

RESTART=false
if [ "$1" == "--restart" ]; then
    RESTART=true
fi

POSTGRES_PORT="${DEEPSQL_POSTGRES_PORT:-5432}"
VALKEY_PORT="${DEEPSQL_VALKEY_PORT:-6379}"

echo -e "${BLUE}🚀 Starting DBA Agent Services${NC}"
echo "======================================"
echo ""

if [ -f "$ENV_FILE" ]; then
    echo -e "${BLUE}🔐 Loading environment from .env${NC}"
    set -a
    source "$ENV_FILE"
    set +a
    if [ "${SPRING_PROFILES_ACTIVE:-}" = "prod" ]; then
        echo -e "${YELLOW}⚠${NC} Local source-run startup ignores SPRING_PROFILES_ACTIVE=prod from .env"
        unset SPRING_PROFILES_ACTIVE
    fi
    echo -e "${GREEN}✓${NC} Loaded local runtime environment"
    echo ""
fi

build_backend_launch_command() {
    local mvn_command="$1"
    local env_snippet=""
    if [ -f "$ENV_FILE" ]; then
        env_snippet="set -a && source \"$ENV_FILE\" && set +a && if [ \"\${SPRING_PROFILES_ACTIVE:-}\" = \"prod\" ]; then unset SPRING_PROFILES_ACTIVE; fi && "
    fi
    printf '%s' "${env_snippet}cd \"$PROJECT_ROOT/backend\" && exec ${mvn_command} spring-boot:run"
}

# Function to check if a port is in use
is_port_in_use() {
    lsof -Pi :$1 -sTCP:LISTEN -t >/dev/null 2>&1
}

# Function to kill process on a port
kill_port() {
    local port=$1
    local service=$2
    if is_port_in_use $port; then
        echo -e "${YELLOW}⚠${NC} Stopping existing $service on port $port..."
        lsof -ti:$port | xargs kill -9 2>/dev/null || true
        sleep 2
    fi
}

kill_orphan_processes() {
    local pattern="$1"
    local service="$2"
    if pgrep -f "$pattern" >/dev/null 2>&1; then
        echo -e "${YELLOW}⚠${NC} Stopping orphan $service processes..."
        pkill -f "$pattern" 2>/dev/null || true
        sleep 2
    fi
}

docker_compose_service_running() {
    local compose_cmd="$1"
    local service="$2"
    $compose_cmd ps -q "$service" 2>/dev/null | grep -q .
}

should_skip_docker_infra() {
    local compose_cmd="$1"

    if is_port_in_use "$POSTGRES_PORT" && ! docker_compose_service_running "$compose_cmd" postgres; then
        echo -e "${YELLOW}⚠${NC} Port $POSTGRES_PORT is already in use by a local PostgreSQL service. Skipping Docker postgres startup."
        return 0
    fi

    if is_port_in_use "$VALKEY_PORT" && ! docker_compose_service_running "$compose_cmd" valkey; then
        echo -e "${YELLOW}⚠${NC} Port $VALKEY_PORT is already in use by a local Redis/Valkey service. Skipping Docker valkey startup."
        return 0
    fi

    return 1
}

# 1. Start PostgreSQL and Valkey using Docker Compose
echo -e "${BLUE}📦 Starting Docker services (PostgreSQL & Valkey)...${NC}"
cd "$PROJECT_ROOT"

if command -v docker &> /dev/null; then
    if command -v docker-compose &> /dev/null || docker compose version &> /dev/null 2>&1; then
        # Use docker compose (newer) or docker-compose (older)
        if docker compose version &> /dev/null 2>&1; then
            DOCKER_COMPOSE_CMD="docker compose"
        else
            DOCKER_COMPOSE_CMD="docker-compose"
        fi
        
        if should_skip_docker_infra "$DOCKER_COMPOSE_CMD"; then
            echo -e "${GREEN}✓${NC} Using existing local infrastructure services"
        else
            if [ "$RESTART" = true ]; then
                echo "Restarting Docker services..."
                $DOCKER_COMPOSE_CMD down
                sleep 2
            fi

            $DOCKER_COMPOSE_CMD up -d postgres valkey

            echo "Waiting for PostgreSQL to be ready..."
            for i in {1..30}; do
                if docker ps --format '{{.Names}}' | grep -q "postgres"; then
                    if docker exec $(docker ps -q -f name=postgres) pg_isready -U postgres >/dev/null 2>&1; then
                        echo -e "${GREEN}✓${NC} PostgreSQL is ready"
                        break
                    fi
                fi
                sleep 1
                if [ $i -eq 30 ]; then
                    echo -e "${YELLOW}⚠${NC} PostgreSQL took too long to start, but continuing..."
                fi
            done

            echo -e "${GREEN}✓${NC} Docker services started"
        fi
    else
        echo -e "${YELLOW}⚠${NC} docker-compose not found. Please install Docker Compose."
        echo "  macOS: Docker Desktop includes docker-compose"
        echo "  Or install: brew install docker-compose"
    fi
else
    echo -e "${YELLOW}⚠${NC} Docker not found. Skipping Docker services."
    echo "  Install Docker Desktop from https://www.docker.com/products/docker-desktop"
    echo ""
    echo "  Or start PostgreSQL manually:"
    echo "    brew services start postgresql@18"
    echo "    createdb dba_agent"
fi

echo ""

# 2. Start Backend
echo -e "${BLUE}☕ Starting Backend (Spring Boot)...${NC}"
cd "$PROJECT_ROOT/backend"

if [ "$RESTART" = true ]; then
    kill_port 8080 "Backend"
    kill_orphan_processes "org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run|com.dbaagent.DbaAgentApplication" "Backend"
fi

if is_port_in_use 8080; then
    echo -e "${YELLOW}⚠${NC} Backend is already running on port 8080"
    echo "  Use --restart flag to restart it"
else
    # Check if Maven wrapper exists
    if [ -f "./mvnw" ]; then
        chmod +x ./mvnw
        echo "Starting backend with Maven wrapper..."
        nohup bash -lc "$(build_backend_launch_command "./mvnw")" > ../backend-dev.log 2>&1 &
        BACKEND_PID=$!
        echo "Backend PID: $BACKEND_PID"
    elif command -v mvn &> /dev/null; then
        echo "Starting backend with system Maven..."
        nohup bash -lc "$(build_backend_launch_command "mvn")" > ../backend-dev.log 2>&1 &
        BACKEND_PID=$!
        echo "Backend PID: $BACKEND_PID"
    else
        echo -e "${YELLOW}⚠${NC} Maven not found!"
        echo "  Install: brew install maven"
        exit 1
    fi
    
    # Wait for backend to start
    echo "Waiting for backend to start..."
    for i in {1..60}; do
        if curl -s http://localhost:8080/api/actuator/health >/dev/null 2>&1; then
            echo -e "${GREEN}✓${NC} Backend is ready"
            break
        fi
        sleep 1
        if [ $i -eq 60 ]; then
            echo -e "${YELLOW}⚠${NC} Backend took too long to start. Check logs: tail -f backend-dev.log"
        fi
    done
fi

echo ""

# 3. Start Frontend
echo -e "${BLUE}⚛️  Starting Frontend (Vite/React)...${NC}"
cd "$PROJECT_ROOT"

if [ "$RESTART" = true ]; then
    kill_port 3000 "Frontend"
    kill_orphan_processes "vite|npm run dev" "Frontend"
fi

if is_port_in_use 3000; then
    echo -e "${YELLOW}⚠${NC} Frontend is already running on port 3000"
    echo "  Use --restart flag to restart it"
else
    # Check if node_modules exists
    if [ ! -d "node_modules" ]; then
        echo "Installing frontend dependencies..."
        npm install
    fi
    
    echo "Starting frontend..."
    nohup npm run dev > frontend-dev.log 2>&1 &
    FRONTEND_PID=$!
    echo "Frontend PID: $FRONTEND_PID"
    
    # Wait for frontend to start
    echo "Waiting for frontend to start..."
    for i in {1..30}; do
        if curl -s http://localhost:3000 >/dev/null 2>&1; then
            echo -e "${GREEN}✓${NC} Frontend is ready"
            break
        fi
        sleep 1
        if [ $i -eq 30 ]; then
            echo -e "${YELLOW}⚠${NC} Frontend took too long to start. Check logs: tail -f frontend-dev.log"
        fi
    done
fi

echo ""
echo -e "${GREEN}✅ All services started!${NC}"
echo ""
echo "📊 Service Status:"
echo "=================="
echo "  PostgreSQL:  http://localhost:5432"
echo "  Valkey:      http://localhost:6379"
echo "  Backend:     http://localhost:8080/api"
echo "  Frontend:    http://localhost:3000"
echo ""
echo "📝 Logs:"
echo "  Backend:  tail -f backend-dev.log"
echo "  Frontend: tail -f frontend-dev.log"
echo ""
echo "🛑 To stop all services:"
echo "  ./scripts/stop-all.sh"
echo ""
echo "🔍 To check service status:"
echo "  ./scripts/check-services.sh"
