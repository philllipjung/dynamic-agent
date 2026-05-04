package com.javaagent.test.spark.service;

import com.javaagent.test.spark.dto.JobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for submitting Spark jobs
 */
@Service
public class SparkJobService {

    private static final Logger log = LoggerFactory.getLogger(SparkJobService.class);

    public void submitSparkJob(JobRequest request, String traceparent) {
        log.info("Submitting Spark job with traceparent: {}", traceparent);

        try {
            // Build spark-submit command
            List<String> command = new ArrayList<>();
            command.add("spark-submit");
            command.add("--class");
            command.add("com.javaagent.test.spark.job.WordCountJob");
            command.add("--master");
            command.add("local[*]");

            // Pass traceparent via Spark configuration
            command.add("--conf");
            command.add("spark.driver.extraJavaOptions=-Dtraceparent=" + traceparent);
            command.add("--conf");
            command.add("spark.executor.extraJavaOptions=-Dtraceparent=" + traceparent);

            command.add("../spark-test-services/spark-job/target/spark-test-job-1.0.0.jar");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            log.info("Executing: {}", String.join(" ", command));

            Process process = pb.start();

            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[SPARK] {}", line);
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("✓ Spark job completed successfully");
            } else {
                log.error("✗ Spark job failed with exit code: {}", exitCode);
            }

        } catch (Exception e) {
            log.error("Failed to submit Spark job", e);
            throw new RuntimeException("Spark job submission failed", e);
        }
    }
}
