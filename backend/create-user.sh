#!/bin/bash

# Simple script to launch the User Management CLI
# Usage: ./create-user.sh

set -e

echo "🚀 Launching User Management CLI..."
echo ""

cd "$(dirname "$0")"

# Check if we're in the backend directory
if [ ! -f "pom.xml" ]; then
    echo "❌ Error: pom.xml not found. Please run this script from the backend directory."
    exit 1
fi

# Run the CLI using Maven on port 9090 to avoid conflicts with running backend
mvn spring-boot:run \
  -Dspring-boot.run.main-class=com.dbaagent.cli.UserManagementCLI \
  -Dspring-boot.run.arguments="--server.port=9090" \
  -q
