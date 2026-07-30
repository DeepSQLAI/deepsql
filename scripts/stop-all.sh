#!/bin/bash

# Stop all DBA Agent services
# Usage: ./scripts/stop-all.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🛑 Stopping DBA Agent Services${NC}"
echo "======================================"
echo ""

cd "$PROJECT_ROOT"

# Function to kill process on a port
kill_port() {
    local port=$1
    local service=$2
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        echo -e "${YELLOW}Stopping $service on port $port...${NC}"
        lsof -ti:$port | xargs kill -9 2>/dev/null || true
        sleep 1
        echo -e "${RED}✓${NC} $service stopped"
    else
        echo -e "${YELLOW}⚠${NC} $service not running on port $port"
    fi
}

kill_orphan_processes() {
    local pattern="$1"
    local service="$2"
    if pgrep -f "$pattern" >/dev/null 2>&1; then
        echo -e "${YELLOW}Stopping orphan $service processes...${NC}"
        pkill -f "$pattern" 2>/dev/null || true
        sleep 1
        echo -e "${RED}✓${NC} Orphan $service processes stopped"
    fi
}

# Stop Frontend (port 3000)
kill_port 3000 "Frontend"
kill_orphan_processes "vite|npm run dev" "Frontend"

# Stop Backend (port 8080)
kill_port 8080 "Backend"
kill_orphan_processes "org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run|com.dbaagent.DbaAgentApplication" "Backend"

# Stop Docker services
if command -v docker &> /dev/null; then
    if docker compose version &> /dev/null 2>&1; then
        DOCKER_COMPOSE_CMD="docker compose"
    elif command -v docker-compose &> /dev/null; then
        DOCKER_COMPOSE_CMD="docker-compose"
    else
        DOCKER_COMPOSE_CMD=""
    fi
    
    if [ -n "$DOCKER_COMPOSE_CMD" ]; then
        echo ""
        echo -e "${YELLOW}Stopping Docker services...${NC}"
        $DOCKER_COMPOSE_CMD down
        echo -e "${RED}✓${NC} Docker services stopped"
    fi
fi

echo ""
echo -e "${RED}✅ All services stopped${NC}"
