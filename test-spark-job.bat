@echo off
REM Test Spark Job with traceparent propagation

echo ========================================
echo   Testing Spark Job with Trace Continuity
echo ========================================
echo.

set TRACEPARENT=00-abc123def45678901234567890123456-1234567890123456-01
set OTEL_EXPORTER_OTLP_ENDPOINT=http://192.168.201.166:4317
set JOB_NAME=test-wordcount

echo Traceparent: %TRACEPARENT%
echo OTEL Endpoint: %OTEL_EXPORTER_OTLP_ENDPOINT%
echo Job Name: %JOB_NAME%
echo.

echo Testing Spark Job instrumentation...
echo.

java ^
  -javaagent:spark-integration/otel-extension/target/spark-otel-extension-1.0.0.jar ^
  -Dtraceparent=%TRACEPARENT% ^
  -Dotel.service.name=spark-job-test ^
  -Dotel.exporter.otlp.endpoint=%OTEL_EXPORTER_OTLP_ENDPOINT% ^
  -DJOB_NAME=%JOB_NAME% ^
  -cp spark-test-services/spark-job/target/spark-test-job-1.0.0.jar ^
  com.javaagent.test.spark.job.WordCountJob

echo.
echo ========================================
echo   Spark Job Test Complete
echo ========================================
pause
