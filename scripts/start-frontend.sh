#!/bin/bash

# Start Frontend (Next.js)
echo "Starting DBA Agent Frontend..."
echo "================================"

cd "$(dirname "$0")"

# Check if node_modules exists
if [ ! -d "node_modules" ]; then
    echo "Installing dependencies..."
    npm install
fi

# Check if .env.local exists
if [ ! -f ".env.local" ]; then
    echo "Creating .env.local file..."
    echo "NEXT_PUBLIC_API_URL=http://localhost:8080" > .env.local
fi

echo "Starting Next.js dev server..."
npm run dev


