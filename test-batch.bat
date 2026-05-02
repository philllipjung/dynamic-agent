@echo off
REM Test Batch Processor with Spark OTEL Extension

echo ========================================
echo   Testing Batch Processor
echo ========================================
echo.

set OTEL_EXPORTER_OTLP_ENDPOINT=http://192.168.201.166:4317
set OPENSEARCH_URL=http://192.168.201.166:9200
set BATCH_QUEUE_FILE=./data/batch-queue.json

echo Starting Batch Processor...
echo OTEL Endpoint: %OTEL_EXPORTER_OTLP_ENDPOINT%
echo OpenSearch URL: %OPENSEARCH_URL%
echo Batch Queue: %BATCH_QUEUE_FILE%
echo.

java ^
  -javaagent:spark-integration/otel-extension/target/spark-otel-extension-1.0.0.jar ^
  -Dotel.service.name=batch-processor ^
  -Dotel.exporter.otlp.endpoint=%OTEL_EXPORTER_OTLP_ENDPOINT% ^
  -Dopensearch.url=%OPENSEARCH_URL% ^
  -cp spark-integration/batch-processor/target/batch-processor-1.0.0.jar ^
  com.javaagent.spark.batch.OpenSearchBatchProcessor

pause
