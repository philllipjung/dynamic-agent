package com.javaagent.bytebuddy.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.commons.AgentConstants;
import com.javaagent.commons.TraceLink;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Span Link Manager - OpenSearch에서 Trace 연결 정보 조회
 *
 * 속성 값으로 매칭되는 Trace를 찾아 스팬 링크 생성
 */
public class SpanLinkManager {

    private static RestClient client;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static String indexPattern = AgentConstants.DEFAULT_OPENSEARCH_INDEX;

    private static String osHost = System.getProperty(AgentConstants.PROP_OPENSEARCH_HOST, AgentConstants.DEFAULT_OPENSEARCH_HOST);

    static {
        initializeClient();
    }

    private static synchronized void initializeClient() {
        if (client != null) return;

        try {
            client = RestClient.builder(HttpHost.create(osHost)).build();
            System.out.println("[SpanLinkManager] Initialized with host: " + osHost);
        } catch (Exception e) {
            System.err.println("[SpanLinkManager] Failed to initialize: " + e.getMessage());
        }
    }

    /**
     * 속성 값으로 매칭되는 타겟 Trace 찾기
     *
     * @param targetSpanName 타겟 스팬명
     * @param targetAttributeKey 타겟 속성 키
     * @param attributeValue 찾을 속성 값
     * @return 매칭된 Trace 목록
     */
    public static List<TraceLink> findMatchingTraces(
            String targetSpanName,
            String targetAttributeKey,
            String attributeValue
    ) {
        List<TraceLink> traces = new ArrayList<>();

        if (client == null) {
            System.err.println("[SpanLinkManager] Client not initialized");
            return traces;
        }

        try {
            String query = buildMatchQuery(targetSpanName, targetAttributeKey, attributeValue);

            Request request = new Request("POST", "/" + indexPattern + "/_search");
            request.setEntity(new NStringEntity(query, ContentType.APPLICATION_JSON));

            Response response = client.performRequest(request);
            String responseBody = new String(response.getEntity().getContent().readAllBytes());

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode hits = root.path("hits").path("hits");

            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");

                TraceLink traceLink = new TraceLink();
                traceLink.setTraceID(source.path("traceID").asText());
                traceLink.setSpanID(source.path("spanID").asText());
                traceLink.setOperationName(source.path("operationName").asText());

                Map<String, String> attributes = extractAttributes(source);
                traceLink.setAttributes(attributes);

                traces.add(traceLink);
            }

            System.out.println("[SpanLinkManager] Found " + traces.size() +
                    " matching traces for " + attributeValue);

        } catch (Exception e) {
            System.err.println("[SpanLinkManager] Error: " + e.getMessage());
        }

        return traces;
    }

    /**
     * 매칭 쿼리 구성
     */
    private static String buildMatchQuery(String spanName, String attrKey, String attrValue) {
        return "{"
                + "\"size\": 100,"
                + "\"query\": {"
                + "  \"bool\": {"
                + "    \"must\": ["
                + "      {\"term\": {\"operationName\": \"" + spanName + "\"}},"
                + "      {\"term\": {\"tags.key\": \"" + attrKey + "\"}},"
                + "      {\"term\": {\"tags.value\": \"" + attrValue + "\"}}"
                + "    ]"
                + "  }"
                + "}"
                + "}";
    }

    /**
     * 스팬에서 속성 추출
     */
    private static Map<String, String> extractAttributes(JsonNode source) {
        Map<String, String> attributes = new java.util.HashMap<>();

        JsonNode tagsArray = source.path("tags");
        if (tagsArray.isArray()) {
            for (JsonNode tag : tagsArray) {
                String key = tag.path("key").asText();
                String value = tag.path("value").asText();

                if (key.startsWith("arthas.attribute.")) {
                    attributes.put(key, value);
                }
            }
        }

        return attributes;
    }

    /**
     * 인덱스 패턴 설정
     */
    public static void setIndexPattern(String pattern) {
        indexPattern = pattern;
        System.out.println("[SpanLinkManager] Index pattern set to: " + pattern);
    }

    /**
     * OpenSearch 호스트 설정
     */
    public static void configure(String host) {
        osHost = host;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                System.err.println("[SpanLinkManager] Error closing client: " + e.getMessage());
            }
        }
        client = null;
        initializeClient();
    }
}
