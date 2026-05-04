#!/bin/bash

echo "=== Restarting Jaeger ==="

# Stop Jaeger
echo "Stopping Jaeger..."
pkill -f "jaeger.*--config"
sleep 3

# Verify stopped
if ps aux | grep "jaeger.*--config" | grep -v grep | grep -q .; then
    echo "Failed to stop Jaeger, forcing..."
    pkill -9 -f "jaeger.*--config"
    sleep 2
fi

# Start Jaeger
echo "Starting Jaeger..."
/root/test/jaeger/jaeger --config=/root/test/config/jaeger-local.yaml > /root/test/logs/jaeger-current.log 2>&1 &

# Wait for startup
echo "Waiting for Jaeger to start..."
sleep 5

# Verify
if ps aux | grep "jaeger.*--config" | grep -v grep | grep -q .; then
    echo "✅ Jaeger started successfully"
    ps aux | grep "jaeger.*--config" | grep -v grep
else
    echo "❌ Jaeger failed to start"
    tail -20 /root/test/logs/jaeger-current.log
    exit 1
fi

# Check ports
echo ""
echo "Checking ports..."
for port in 16686 16685 16687 4317 4318; do
    if ss -tlnp 2>/dev/null | grep -q ":$port"; then
        echo "✅ Port $port is listening"
    else
        echo "❌ Port $port is NOT listening"
    fi
done

# Test API
echo ""
echo "Testing API..."
if curl -s "http://localhost:16686/api/services" > /dev/null 2>&1; then
    echo "✅ Jaeger API is responding"
else
    echo "❌ Jaeger API is NOT responding"
fi

echo ""
echo "=== Restart complete ==="
