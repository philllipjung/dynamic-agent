package com.javaagent.test.spark.controller;

import com.javaagent.test.spark.dto.JobRequest;
import com.javaagent.test.spark.dto.JobResponse;
import com.javaagent.test.spark.service.SparkJobService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for Spark Job submission
 */
@RestController
@RequestMapping("/api/spark")
public class SparkJobController {

    private static final Logger log = LoggerFactory.getLogger(SparkJobController.class);

    @Autowired(required = false)
    private Tracer tracer;

    @Autowired
    private SparkJobService sparkJobService;

    @PostMapping("/jobs/submit")
    public ResponseEntity<JobResponse> submitJob(@RequestBody JobRequest request) {
        log.info("========================================");
        log.info("  Spark Job Submission");
        log.info("========================================");
        log.info("Job Name: {}", request.getJobName());
        log.info("Input Path: {}", request.getInputPath());
        log.info("Output Path: {}", request.getOutputPath());

        // Get current trace context
        Span currentSpan = Span.fromContextOrNull(Context.current());
        String traceId = currentSpan != null ? currentSpan.getSpanContext().getTraceId() : "unknown";
        String spanId = currentSpan != null ? currentSpan.getSpanContext().getSpanId() : "unknown";

        log.info("Current Trace ID: {}", traceId);
        log.info("Current Span ID: {}", spanId);

        // Create traceparent
        String traceparent = String.format("00-%s-%s-01", traceId, spanId);
        log.info("Generated traceparent: {}", traceparent);

        // Submit Spark job with traceparent
        String jobId = UUID.randomUUID().toString();
        sparkJobService.submitSparkJob(request, traceparent);

        JobResponse response = JobResponse.builder()
                .jobId(jobId)
                .status("SUBMITTED")
                .traceparent(traceparent)
                .traceId(traceId)
                .build();

        log.info("✓ Job submitted successfully");
        log.info("========================================");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobResponse> getJobStatus(@PathVariable String jobId) {
        JobResponse response = JobResponse.builder()
                .jobId(jobId)
                .status("COMPLETED")
                .build();
        return ResponseEntity.ok(response);
    }
}
