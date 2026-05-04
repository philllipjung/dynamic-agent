#!/bin/bash
# Start all test services

cd "$(dirname "$0")"

echo "========================================="
echo "Test Services - Starting All"
echo "========================================="
echo ""

# Kill any existing services on ports 8081 and 8082
echo "[INFO] Stopping any existing services..."
fuser -k 8081/tcp 2>/dev/null || true
fuser -k 8082/tcp 2>/dev/null || true
sleep 2

echo "[INFO] Starting Test1 Service..."
gnome-terminal -- bash -c "cd '$(pwd)' && ./run-test1.sh" &

sleep 3

echo "[INFO] Starting Test2 Service..."
gnome-terminal -- bash -c "cd '$(pwd)' && ./run-test2.sh" &

echo ""
echo "========================================="
echo "Services Starting..."
echo "  Test1: http://localhost:8081/test1"
echo "  Test2: http://localhost:8082/test2"
echo "========================================="
