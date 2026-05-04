package com.example.server1.controller;

import com.example.server1.grpc.MLClassificationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MLClassificationController {

    private static final Logger log = LoggerFactory.getLogger(MLClassificationController.class);
    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Autowired
    private MLClassificationClient mlClassificationClient;

    @PostMapping("/api/ml/classification")
    public ResponseEntity<Map<String, Object>> submitClassificationJob(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = SESSION_ID_HEADER, required = false) String sessionId) {

        // Add to MDC for JSON logging
        MDC.put("requestId", requestId != null ? requestId : "unknown");
        MDC.put("sessionId", sessionId != null ? sessionId : "unknown");

        try {
            String jobId = (String) request.get("job_id");
            String inputPath = (String) request.get("input_path");
            String outputPath = (String) request.get("output_path");
            String modelType = (String) request.get("model_type");

            log.info("REST-to-gRPC: Forwarding ML Classification Job to gRPC Server - JobId: {}", jobId);

            // Forward to gRPC server
            Map<String, Object> response = mlClassificationClient.submitClassificationJob(
                jobId, inputPath, outputPath, modelType
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to submit classification job via gRPC", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", e.getMessage()
            ));
        } finally {
            MDC.remove("requestId");
            MDC.remove("sessionId");
        }
    }
}
