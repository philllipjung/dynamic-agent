package com.javaagent.spark.otel;

import com.javaagent.spark.batch.BatchInstrumentation;
import com.javaagent.spark.instrumentation.SparkJobInstrumentation;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Spark OpenTelemetry Java Agent
 *
 * Auto-instrumentation agent for:
 * - Spark Jobs (trace continuity from parent service)
 * - Batch Processing (span links to original traces)
 *
 * Usage:
 *   java -javaagent:spark-otel-extension-1.0.0.jar -jar your-app.jar
 */
public class SparkOtelAgent {

    private static final Logger log = Logger.getLogger(SparkOtelAgent.class.getName());

    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("========================================");
        log.info("  Spark OTEL Extension Agent");
        log.info("  Version: 1.0.0");
        log.info("========================================");

        try {
            // Install Spark Job Instrumentation
            log.info("[SparkOtelAgent] Installing Spark Job instrumentation...");
            SparkJobInstrumentation.install(inst);
            log.info("[SparkOtelAgent] ✓ Spark Job instrumentation installed");

            // Install Batch Processing Instrumentation
            log.info("[SparkOtelAgent] Installing Batch Processing instrumentation...");
            BatchInstrumentation.install(inst);
            log.info("[SparkOtelAgent] ✓ Batch Processing instrumentation installed");

            log.info("========================================");
            log.info("  Spark OTEL Extension initialized!");
            log.info("========================================");

        } catch (Exception e) {
            log.severe("[SparkOtelAgent] Failed to initialize: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        // For dynamic attach (not commonly used for this agent)
        premain(agentArgs, inst);
    }
}
