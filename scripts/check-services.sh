#!/bin/bash

# Check if services are running
# Usage: ./scripts/check-services.sh

echo "🔍 Checking DBA Agent Services Status"
echo "======================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to check if a port is in use
check_port() {
    local port=$1
    local service=$2
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1 ; then
        echo -e "${GREEN}✓${NC} $service is running on port $port"
        return 0
    else
        echo -e "${RED}✗${NC} $service is NOT running on port $port"
        return 1
    fi
}

# Function to check Docker service
check_docker_service() {
    local service=$1
    if command -v docker &> /dev/null; then
        if docker ps --format '{{.Names}}' | grep -q "^${service}$"; then
            echo -e "${GREEN}✓${NC} Docker service '$service' is running"
            return 0
        else
            echo -e "${RED}✗${NC} Docker service '$service' is NOT running"
            return 1
        fi
    else
        echo -e "${YELLOW}⚠${NC} Docker not installed - skipping Docker service checks"
        return 1
    fi
}

# Check PostgreSQL (port 5432)
echo "📊 Database Services:"
check_port 5432 "PostgreSQL"
POSTGRES_RUNNING=$?

# Check Valkey/Redis (port 6379)
check_port 6379 "Valkey/Redis"
VALKEY_RUNNING=$?

echo ""
echo "🚀 Application Services:"

# Check Backend (port 8080)
check_port 8080 "Backend (Spring Boot)"
BACKEND_RUNNING=$?

# Check Frontend (port 3000)
check_port 3000 "Frontend (Vite/React)"
FRONTEND_RUNNING=$?

echo ""
echo "📋 Summary:"
echo "==========="

if [ $POSTGRES_RUNNING -eq 0 ] && [ $VALKEY_RUNNING -eq 0 ] && [ $BACKEND_RUNNING -eq 0 ] && [ $FRONTEND_RUNNING -eq 0 ]; then
    echo -e "${GREEN}All services are running!${NC}"
    exit 0
else
    echo -e "${RED}Some services are not running.${NC}"
    echo ""
    echo "To start all services, run:"
    echo "  ./scripts/start-all.sh"
    exit 1
fi
