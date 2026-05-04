#!/bin/bash
# Test2 Service Startup Script with OpenTelemetry Java Agent

cd "$(dirname "$0")"

echo "========================================="
echo "Test2 Service - Starting with OTEL Agent"
echo "========================================="
echo ""

# Check if OTEL Java Agent exists
OTEL_AGENT_VERSION="1.38.0"
OTEL_AGENT_JAR="$HOME/.otel/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar"

if [ ! -f "$OTEL_AGENT_JAR" ]; then
    echo "[INFO] Downloading OpenTelemetry Java Agent..."
    mkdir -p "$HOME/.otel"
    curl -L -o "$OTEL_AGENT_JAR" \
        "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
    echo "[INFO] Download complete: $OTEL_AGENT_JAR"
fi

echo "[INFO] OpenTelemetry Agent: $OTEL_AGENT_JAR"
echo "[INFO] Service will start on: http://localhost:8082"
echo "[INFO] OTEL Traces exporting to: http://localhost:4317"
echo ""

cd test2-service

# Start Test2 Service with OTEL Agent
java -javaagent:"$OTEL_AGENT_JAR" \
    -Dotel.service.name=test2-service \
    -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
    -Dotel.metrics.exporter=none \
    -jar target/test2-service-1.0.0.jar
