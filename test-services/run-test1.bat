@echo off
REM Test1 Service Startup Script with OpenTelemetry Java Agent

cd /d "%~dp0"

echo =========================================
echo Test1 Service - Starting with OTEL Agent
echo =========================================
echo.

REM Check if OTEL Java Agent exists
set OTEL_AGENT_VERSION=1.38.0
set OTEL_AGENT_JAR=%USERPROFILE%\.otel\opentelemetry-javaagent.jar

if not exist "%OTEL_AGENT_JAR%" (
    echo [INFO] Downloading OpenTelemetry Java Agent...
    if not exist "%USERPROFILE%\.otel" mkdir "%USERPROFILE%\.otel"
    curl -L -o "%OTEL_AGENT_JAR%" https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v%OTEL_AGENT_VERSION%/opentelemetry-javaagent.jar
    echo [INFO] Download complete: %OTEL_AGENT_JAR%
)

echo [INFO] OpenTelemetry Agent: %OTEL_AGENT_JAR%
echo [INFO] Service will start on: http://localhost:8081
echo [INFO] OTEL Traces exporting to: http://localhost:4317
echo.

cd test1-service

REM Start Test1 Service with OTEL Agent
java -javaagent:"%OTEL_AGENT_JAR%" ^
    -Dotel.service.name=test1-service ^
    -Dotel.exporter.otlp.endpoint=http://localhost:4317 ^
    -Dotel.metrics.exporter=none ^
    -jar target/test1-service-1.0.0.jar
