package com.javaagent.server.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.commons.AgentConstants;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenSearchManager - Manages OpenSearch queries for Jaeger spans
 *
 * This service queries the jaeger-span-* index to retrieve span names
 * and attributes (tags) for the UI.
 *
 * Configuration (via configure() method):
 * - opensearch.host (required)
 * - opensearch.index.pattern (required)
 *
 * Jaeger Index Format:
 * - Index: jaeger-span-YYYY-MM-DD
 * - Fields: operationName, serviceName, tags (array of key-value pairs)
 */
public class OpenSearchManager {

    private static String osHost;
    private static String indexPattern;

    private static RestClient client;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static boolean initialized = false;

    // Static initializer
    static {
        osHost = System.getProperty(AgentConstants.PROP_OPENSEARCH_HOST);
        indexPattern = System.getProperty(AgentConstants.PROP_OPENSEARCH_INDEX);
        if (osHost != null && indexPattern != null) {
            initializeClient();
        } else {
            System.out.println("[OpenSearchManager] Waiting for configuration");
        }
    }

    private static synchronized void initializeClient() {
        if (initialized) return;

        // Use defaults only if not configured (should not happen after AgentConfiguration runs)
        if (osHost == null || osHost.isEmpty()) {
            osHost = AgentConstants.DEFAULT_OPENSEARCH_HOST;
        }
        if (indexPattern == null || indexPattern.isEmpty()) {
            indexPattern = AgentConstants.DEFAULT_OPENSEARCH_INDEX;
        }

        try {
            // Create low-level REST client (works with both ES and OS)
            client = RestClient.builder(HttpHost.create(osHost)).build();
            initialized = true;
            System.out.println("[OpenSearchManager] Initialized with host: " + osHost + ", index: " + indexPattern);
        } catch (Exception e) {
            System.err.println("[OpenSearchManager] Failed to initialize client: " + e.getMessage());
        }
    }

    /**
     * Configure OpenSearch settings programmatically
     */
    public static void configure(String host, String pattern) {
        try {
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("opensearch.host must be configured");
            }
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("opensearch.index.pattern must be configured");
            }
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    System.err.println("[OpenSearchManager] Error closing client: " + e.getMessage());
                }
            }
            osHost = host;
            indexPattern = pattern;
            initializeClient();
        } catch (Exception e) {
            System.err.println("[OpenSearchManager] Failed to configure: " + e.getMessage());
        }
    }

    /**
     * Get current index pattern
     */
    public static String getIndexPattern() {
        if (!initialized) {
            initializeClient();
        }
        return indexPattern;
    }

    /**
     * Get current OpenSearch host
     */
    public static String getOsHost() {
        if (!initialized) {
            initializeClient();
        }
        return osHost;
    }

    /**
     * Get unique span names from Jaeger spans
     * Jaeger format: operationName field
     *
     * @return List of unique span names (operationName)
     */
    public static List<String> getSpanNames() {
        List<String> spanNames = new ArrayList<>();

        if (!initialized) {
            initializeClient();
        }

        if (client == null) {
            System.err.println("[OpenSearchManager] Client not initialized");
            return spanNames;
        }

        try {
            // Build aggregation query for Jaeger format
            String query = "{"
                + "\"size\": 0,"
                + "\"aggs\": {"
                + "  \"operations\": {"
                + "    \"terms\": {"
                + "      \"field\": \"operationName\","
                + "      \"size\": 1000"
                + "    }"
                + "  }"
                + "}"
                + "}";

            Request request = new Request("POST", "/" + getIndexPattern() + "/_search");
            request.setEntity(new NStringEntity(query, ContentType.APPLICATION_JSON));

            Response response = client.performRequest(request);
            String responseBody = new String(response.getEntity().getContent().readAllBytes());

            // Parse JSON response
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode aggregations = root.path("aggregations");

            if (aggregations.has("operations")) {
                JsonNode buckets = aggregations.path("operations").path("buckets");
                if (buckets.isArray()) {
                    Set<String> uniqueNames = new HashSet<>();
                    for (JsonNode bucket : buckets) {
                        String key = bucket.path("key").asText();
                        if (key != null && !key.isEmpty()) {
                            uniqueNames.add(key);
                        }
                    }
                    spanNames.addAll(uniqueNames);
                }
            }

            System.out.println("[OpenSearchManager] Found " + spanNames.size() + " unique span names");

        } catch (Exception e) {
            System.err.println("[OpenSearchManager] Failed to query span names: " + e.getMessage());
        }

        return spanNames;
    }

    /**
     * Get span attributes (tags) for a specific span name
     * Jaeger format: tags array with key-value pairs
     *
     * @param spanName Operation name (e.g., "/FindDriverIDs")
     * @return List of unique attribute keys (tags.key) for this span
     */
    public static List<String> getSpanAttributes(String spanName) {
        List<String> attributes = new ArrayList<>();

        if (!initialized) {
            initializeClient();
        }

        if (client == null) {
            System.err.println("[OpenSearchManager] Client not initialized");
            return attributes;
        }

        try {
            // Build query to filter by operation name and aggregate tag keys
            String query = "{"
                + "\"size\": 0,"
                + "\"query\": {"
                + "  \"term\": { \"operationName\": \"" + spanName + "\" }"
                + "},"
                + "\"aggs\": {"
                + "  \"tag_keys\": {"
                + "    \"nested\": {"
                + "      \"path\": \"tags\""
                + "    },"
                + "    \"aggs\": {"
                + "      \"keys\": {"
                + "        \"terms\": {"
                + "          \"field\": \"tags.key\","
                + "          \"size\": 1000"
                + "        }"
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "}";

            Request request = new Request("POST", "/" + getIndexPattern() + "/_search");
            request.setEntity(new NStringEntity(query, ContentType.APPLICATION_JSON));

            Response response = client.performRequest(request);
            String responseBody = new String(response.getEntity().getContent().readAllBytes());

            // Parse JSON response
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode aggregations = root.path("aggregations");

            if (aggregations.has("tag_keys")) {
                JsonNode keysAgg = aggregations.path("tag_keys");
                if (keysAgg.has("keys")) {
                    JsonNode buckets = keysAgg.path("keys").path("buckets");
                    if (buckets.isArray()) {
                        Set<String> uniqueAttrs = new HashSet<>();
                        for (JsonNode bucket : buckets) {
                            String key = bucket.path("key").asText();
                            if (key != null && !key.isEmpty()) {
                                uniqueAttrs.add(key);
                            }
                        }
                        attributes.addAll(uniqueAttrs);
                    }
                }
            }

            // Also get sample documents to find more attributes
            List<String> docAttributes = getAttributesFromSampleDocuments(spanName, 3);
            attributes.addAll(docAttributes);

            // Remove duplicates and sort
            Set<String> uniqueAttrs = new HashSet<>(attributes);
            attributes = new ArrayList<>(uniqueAttrs);
            Collections.sort(attributes);

            System.out.println("[OpenSearchManager] Found " + attributes.size() + " attributes for span: " + spanName);

        } catch (Exception e) {
            System.err.println("[OpenSearchManager] Failed to query span attributes: " + e.getMessage());
            e.printStackTrace();
        }

        return attributes;
    }

    /**
     * Get attributes from sample documents
     */
    public static List<String> getAttributesFromSampleDocuments(String spanName, int sampleSize) {
        List<String> attributes = new ArrayList<>();
        Set<String> uniqueAttrs = new HashSet<>();

        if (!initialized) {
            initializeClient();
        }

        if (client == null) {
            System.err.println("[OpenSearchManager] Client not initialized");
            return attributes;
        }

        try {
            // Get sample documents - fetch tags field
            String query = "{"
                + "\"size\": " + sampleSize + ","
                + "\"query\": {"
                + "  \"term\": { \"operationName\": \"" + spanName + "\" }"
                + "}"
                + "}";

            Request request = new Request("POST", "/" + getIndexPattern() + "/_search");
            request.setEntity(new NStringEntity(query, ContentType.APPLICATION_JSON));

            Response response = client.performRequest(request);
            String responseBody = new String(response.getEntity().getContent().readAllBytes());

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode hits = root.path("hits").path("hits");

            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    JsonNode source = hit.path("_source");

                    // Jaeger format: tags is an array of objects with key, value, type
                    JsonNode tagsArray = source.path("tags");
                    if (tagsArray.isArray()) {
                        for (JsonNode tag : tagsArray) {
                            String key = tag.path("key").asText();
                            if (key != null && !key.isEmpty()) {
                                uniqueAttrs.add(key);
                            }
                        }
                    }
                }
            }

            attributes.addAll(uniqueAttrs);

        } catch (Exception e) {
            System.err.println("[OpenSearchManager] Failed to get sample attributes: " + e.getMessage());
            e.printStackTrace();
        }

        return attributes;
    }
}
