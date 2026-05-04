package com.javaagent.commons.config;

/**
 * OpenTelemetry Configuration
 */
public class OtelConfig {

    private static final String DEFAULT_ENDPOINT = "http://localhost:4317";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * Get OTLP endpoint from environment variable or system property
     */
    public static String getOtelEndpoint() {
        // Try environment variable first
        String endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }

        // Try system property
        endpoint = System.getProperty("otel.exporter.otlp.endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }

        // Default endpoint
        return DEFAULT_ENDPOINT;
    }

    /**
     * Get exporter timeout in seconds
     */
    public static int getExporterTimeoutSeconds() {
        String timeout = System.getenv("OTEL_EXPORTER_OTLP_TIMEOUT");
        if (timeout != null && !timeout.isEmpty()) {
            try {
                return Integer.parseInt(timeout);
            } catch (NumberFormatException e) {
                // Use default
            }
        }

        timeout = System.getProperty("otel.exporter.otlp.timeout");
        if (timeout != null && !timeout.isEmpty()) {
            try {
                return Integer.parseInt(timeout);
            } catch (NumberFormatException e) {
                // Use default
            }
        }

        return DEFAULT_TIMEOUT_SECONDS;
    }
}
