#!/bin/bash
# Java Agent System Startup Script

cd "$(dirname "$0")"

echo "========================================="
echo "Java Agent System - Starting Server"
echo "========================================="
echo ""

# Check if Java is available
if ! command -v java &> /dev/null; then
    echo "ERROR: Java is not installed or not in PATH"
    exit 1
fi

echo "Java version:"
java -version
echo ""

# Start the agent server
echo "Starting Agent Server on port 8080..."
echo "Web UI will be available at: http://localhost:8080"
echo ""

cd agent-server
java -jar target/agent-server-1.0.0.jar
