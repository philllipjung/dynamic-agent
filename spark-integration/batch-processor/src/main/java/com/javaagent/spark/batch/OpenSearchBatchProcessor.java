package com.javaagent.spark.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * OpenSearch Span Link Batch Processor
 *
 * Pure business logic - NO OpenTelemetry code
 * All instrumentation (span creation, links) is handled by BatchInstrumentation
 *
 * Business Logic:
 * 1. Read transactionNumber from batch queue file
 * 2. Search OpenSearch for spans with transaction attribute
 * 3. Execute batch processing
 */
public class OpenSearchBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchBatchProcessor.class);

    private final ObjectMapper objectMapper;
    private final String queueFilePath;
    private final String openSearchUrl;

    public OpenSearchBatchProcessor() {
        this.objectMapper = new ObjectMapper();
        // Support configurable queue file path via environment variable
        String envPath = System.getenv("BATCH_QUEUE_FILE");
        this.queueFilePath = envPath != null ? envPath : "./data/batch-queue.json";
        log.info("Batch queue file path: {}", new File(this.queueFilePath).getAbsolutePath());
        this.openSearchUrl = System.getProperty("opensearch.url",
            System.getenv().getOrDefault("OPENSEARCH_URL", "http://192.168.201.166:9200"));
    }

    public void startProcessing() throws InterruptedException {
        log.info("========================================");
        log.info("  OpenSearch Span Link Processor");
        log.info("  Reading from OpenSearch: {}", openSearchUrl);
        log.info("========================================");

        while (true) {
            try {
                processBatches();
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                log.info("Batch processing interrupted");
                throw e;
            } catch (Exception e) {
                log.error("Error in batch processing loop", e);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        OpenSearchBatchProcessor processor = new OpenSearchBatchProcessor();
        processor.startProcessing();
    }

    private void processBatches() {
        try {
            List<Map<String, Object>> batches = readQueueFile();

            if (batches == null || batches.isEmpty()) {
                log.debug("No pending batches");
                return;
            }

            log.info("Processing {} pending batches", batches.size());

            for (Map<String, Object> batchData : batches) {
                processBatch(batchData);
            }

        } catch (Exception e) {
            log.error("Error processing batches", e);
        }
    }

    private void processBatch(Map<String, Object> batchData) {
        String transactionNumber = (String) batchData.get("transactionNumber");
        String batchType = (String) batchData.get("batchType");

        log.info("========================================");
        log.info("Processing Batch");
        log.info("Transaction: {}", transactionNumber);
        log.info("Type: {}", batchType);
        log.info("========================================");

        // Search OpenSearch for transaction attribute
        SpanContextInfo contextInfo = searchTransactionInOpenSearch(transactionNumber);

        if (contextInfo == null) {
            log.warn("Trace not found in OpenSearch for transaction: {}", transactionNumber);
            return;
        }

        log.info("✓ Trace found in OpenSearch!");
        log.info("  Trace ID: {}", contextInfo.traceId);
        log.info("  Span ID: {}", contextInfo.spanId);

        // Execute batch (pure business logic - instrumentation handled by BatchInstrumentation)
        executeBatch(transactionNumber, batchType, contextInfo);
    }

    /**
     * Search OpenSearch for transaction attribute
     */
    private SpanContextInfo searchTransactionInOpenSearch(String transactionNumber) {
        try {
            // OpenSearch Query: Find span with transaction attribute (nested query)
            String query = String.format(
                "{\"query\":{\"nested\":{\"path\":\"tags\",\"query\":{\"bool\":{\"must\":[{\"match\":{\"tags.key\":\"transaction\"}},{\"match\":{\"tags.value\":\"%s\"}}]}}}}}",
                transactionNumber
            );

            // Dynamic index name for today
            String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            URL url = new URL(openSearchUrl + "/jaeger-span-" + today + "/_search");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            // Send request
            conn.getOutputStream().write(query.getBytes());

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = new String(conn.getInputStream().readAllBytes());

                // Parse response
                Map<String, Object> responseData = objectMapper.readValue(response, Map.class);
                Map<String, Object> hits = (Map<String, Object>) responseData.get("hits");

                if (hits != null) {
                    List<Map<String, Object>> hitsList = (List<Map<String, Object>>) hits.get("hits");

                    if (hitsList != null && !hitsList.isEmpty()) {
                        Map<String, Object> firstHit = hitsList.get(0);
                        Map<String, Object> source = (Map<String, Object>) firstHit.get("_source");

                        String traceId = (String) source.get("traceID");
                        String spanId = (String) source.get("spanID");

                        log.info("Found span in OpenSearch - Trace ID: {}, Span ID: {}", traceId, spanId);

                        return SpanContextInfo.builder()
                                .transactionNumber(transactionNumber)
                                .traceId(traceId)
                                .spanId(spanId)
                                .build();
                    }
                }
            }

            log.warn("No transaction found in OpenSearch - HTTP {}", responseCode);
            return null;

        } catch (Exception e) {
            log.error("Error searching OpenSearch", e);
            return null;
        }
    }

    /**
     * Execute batch (pure business logic)
     *
     * BatchInstrumentation will apply advice to this method to:
     * 1. Create span
     * 2. Add span link
     * 3. Connect trace context
     */
    private void executeBatch(String transactionNumber, String batchType, SpanContextInfo contextInfo) {

        log.info("========================================");
        log.info("  Executing Batch");
        log.info("========================================");
        log.info("Transaction: {}", transactionNumber);
        log.info("Batch Type: {}", batchType);
        log.info("Context Info - Trace ID: {}", contextInfo.traceId);
        log.info("Context Info - Span ID: {}", contextInfo.spanId);

        // Execute batch logic
        executeBatchLogic(transactionNumber, batchType);

        log.info("✓ Batch completed successfully");
    }

    private void executeBatchLogic(String transactionNumber, String batchType) {
        log.info("Executing batch logic - Type: {}", batchType);

        try {
            switch (batchType) {
                case "WORDCOUNT":
                    processWordCount(transactionNumber);
                    break;
                case "ETL":
                    processETL(transactionNumber);
                    break;
                default:
                    processDefault(transactionNumber);
            }

        } catch (Exception e) {
            log.error("Batch logic failed", e);
            throw new RuntimeException("Batch logic failed", e);
        }
    }

    private void processWordCount(String transactionNumber) {
        log.info("Processing Word Count for transaction: {}", transactionNumber);

        try {
            Thread.sleep(2000);
            log.info("✓ Word Count completed - Processed 1000 words");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processETL(String transactionNumber) {
        log.info("Processing ETL for transaction: {}", transactionNumber);

        try {
            Thread.sleep(1000);
            log.info("✓ ETL completed - Extracted: 100, Transformed: 100, Loaded: 100");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processDefault(String transactionNumber) {
        log.info("Processing default batch for transaction: {}", transactionNumber);

        try {
            Thread.sleep(1000);
            log.info("✓ Default batch completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Map<String, Object>> readQueueFile() {
        try {
            File file = new File(queueFilePath);

            if (!file.exists()) {
                log.debug("Queue file not found: {}", queueFilePath);
                return null;
            }

            return objectMapper.readValue(file,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, Map.class));

        } catch (Exception e) {
            log.error("Failed to read queue file", e);
            return null;
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class SpanContextInfo {
        private String transactionNumber;
        private String traceId;
        private String spanId;
    }
}
