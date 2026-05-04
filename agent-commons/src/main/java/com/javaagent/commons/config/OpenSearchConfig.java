package com.javaagent.commons.config;

/**
 * OpenSearch Configuration
 *
 * Centralized OpenSearch configuration with priority:
 * 1. System Property (-Dopensearch.url)
 * 2. Environment Variable (OPENSEARCH_URL)
 * 3. Default Value (http://192.168.201.166:9200)
 */
public class OpenSearchConfig {

    private static final String DEFAULT_URL = "http://192.168.201.166:9200";
    private static final String SYSTEM_PROPERTY_KEY = "opensearch.url";
    private static final String ENV_VARIABLE_KEY = "OPENSEARCH_URL";

    /**
     * Get OpenSearch URL with fallback priority
     */
    public static String getOpenSearchUrl() {
        return System.getProperty(SYSTEM_PROPERTY_KEY,
            System.getenv().getOrDefault(ENV_VARIABLE_KEY, DEFAULT_URL));
    }

    /**
     * Get OpenSearch port
     */
    public static int getOpenSearchPort() {
        String port = System.getProperty("opensearch.port",
            System.getenv().getOrDefault("OPENSEARCH_PORT", "9200"));
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return 9200;
        }
    }

    /**
     * Get Jaeger span index pattern for today
     */
    public static String getJaegerSpanIndexForToday() {
        return "jaeger-span-" + java.time.LocalDate.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        );
    }

    /**
     * Get full OpenSearch URL for span index search
     */
    public static String getSpanIndexSearchUrl() {
        return getOpenSearchUrl() + "/" + getJaegerSpanIndexForToday() + "/_search";
    }
}
