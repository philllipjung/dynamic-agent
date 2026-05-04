package com.javaagent.commons.config;

/**
 * OpenTelemetry Configuration
 *
 * Centralized OTEL endpoint configuration with priority:
 * 1. System Property (-Dotel.exporter.otlp.endpoint)
 * 2. Environment Variable (OTEL_EXPORTER_OTLP_ENDPOINT)
 * 3. Default Value (http://192.168.201.166:4317)
 */
public class OtelConfig {

    private static final String DEFAULT_ENDPOINT = "http://192.168.201.166:4317";
    private static final String SYSTEM_PROPERTY_KEY = "otel.exporter.otlp.endpoint";
    private static final String ENV_VARIABLE_KEY = "OTEL_EXPORTER_OTLP_ENDPOINT";

    /**
     * Get OTEL Exporter endpoint with fallback priority
     */
    public static String getOtelEndpoint() {
        return System.getProperty(SYSTEM_PROPERTY_KEY,
            System.getenv().getOrDefault(ENV_VARIABLE_KEY, DEFAULT_ENDPOINT));
    }

    /**
     * Get OTEL service name
     */
    public static String getServiceName() {
        return System.getProperty("otel.service.name",
            System.getenv().getOrDefault("OTEL_SERVICE_NAME", "java-agent-system"));
    }

    /**
     * Get OTEL exporter timeout in seconds
     */
    public static int getExporterTimeoutSeconds() {
        String timeout = System.getProperty("otel.exporter.timeout",
            System.getenv().getOrDefault("OTEL_EXPORTER_TIMEOUT", "30"));
        try {
            return Integer.parseInt(timeout);
        } catch (NumberFormatException e) {
            return 30;
        }
    }
}
