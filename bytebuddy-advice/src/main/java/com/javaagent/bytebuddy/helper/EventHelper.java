package com.javaagent.bytebuddy.helper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * EventHelper - Captures and stores HTTP request/response events for Spring Filters
 *
 * Features:
 * - Captures request headers and body
 * - Captures response headers and body
 * - Stores events in Redis via EventService for UI display
 * - Thread-safe event storage
 */
@SuppressWarnings("unchecked")
public class EventHelper {
    private static final ThreadLocal<String> currentEventId = new ThreadLocal<>();

    private final String eventId;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private EventData eventData;

    public EventHelper(HttpServletRequest request, HttpServletResponse response) {
        this.eventId = generateEventId();
        this.request = request;
        this.response = response;
        this.eventData = new EventData();
        currentEventId.set(eventId);
    }

    /**
     * Capture request information (headers and body)
     */
    public EventHelper captureRequest() {
        try {
            eventData.setMethod(request.getMethod());
            eventData.setRequestUri(request.getRequestURI());
            eventData.setQueryString(request.getQueryString());
            eventData.setRequestHeaders(extractHeaders(request));
            eventData.setRequestBody(extractBody(request));
            eventData.setTimestamp(System.currentTimeMillis());
        } catch (Exception e) {
            System.err.println("[EventHelper] Error capturing request: " + e.getMessage());
        }
        return this;
    }

    /**
     * Capture response information (headers and body)
     * If response body is JSON, also parse and store as key-value map
     */
    public EventHelper captureResponse() {
        try {
            eventData.setResponseStatus(response.getStatus());
            eventData.setResponseHeaders(extractResponseHeaders(response));

            // Try to parse response body as JSON if available
            String responseBody = extractResponseBody(response);
            eventData.setResponseBody(responseBody);

            if (responseBody != null && responseBody.trim().startsWith("{")) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> jsonMap = mapper.readValue(responseBody, Map.class);
                    eventData.setResponseBodyJson(jsonMap);
                    System.out.println("[EventHelper] Parsed response JSON with " + jsonMap.size() + " keys");
                } catch (Exception jsonException) {
                    System.out.println("[EventHelper] Response body is not valid JSON");
                }
            }
        } catch (Exception e) {
            System.err.println("[EventHelper] Error capturing response: " + e.getMessage());
        }
        return this;
    }

    /**
     * Save event to Redis via EventService
     */
    public EventHelper save() {
        try {
            // Convert EventData to Map for EventService
            Map<String, Object> eventMap = eventData.toMap();
            eventMap.put("eventId", eventId);

            // Save to Redis via reflection
            Class<?> eventServiceClass = Class.forName("com.javaagent.bytebuddy.redis.EventService");
            java.lang.reflect.Method saveMethod = eventServiceClass.getMethod("saveEvent", String.class, Map.class);
            saveMethod.invoke(null, eventId, eventMap);

            System.out.println("[EventHelper] Event saved to Redis: " + eventId);
        } catch (Exception e) {
            System.err.println("[EventHelper] Failed to save event to Redis: " + e.getMessage());
            e.printStackTrace();
        }
        return this;
    }

    /**
     * Get all events from Redis
     */
    public static List<Map<String, Object>> getAllEvents() {
        try {
            Class<?> eventServiceClass = Class.forName("com.javaagent.bytebuddy.redis.EventService");
            java.lang.reflect.Method getAllMethod = eventServiceClass.getMethod("getAllEvents");
            return (List<Map<String, Object>>) getAllMethod.invoke(null);
        } catch (Exception e) {
            System.err.println("[EventHelper] Failed to get events from Redis: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get event by ID from Redis
     */
    public static Map<String, Object> getEvent(String eventId) {
        try {
            Class<?> eventServiceClass = Class.forName("com.javaagent.bytebuddy.redis.EventService");
            java.lang.reflect.Method getMethod = eventServiceClass.getMethod("getEvent", String.class);
            return (Map<String, Object>) getMethod.invoke(null, eventId);
        } catch (Exception e) {
            System.err.println("[EventHelper] Failed to get event from Redis: " + e.getMessage());
            return null;
        }
    }

    /**
     * Clear all events from Redis
     */
    public static void clearAllEvents() {
        try {
            Class<?> eventServiceClass = Class.forName("com.javaagent.bytebuddy.redis.EventService");
            java.lang.reflect.Method clearMethod = eventServiceClass.getMethod("clearAllEvents");
            clearMethod.invoke(null);
        } catch (Exception e) {
            System.err.println("[EventHelper] Failed to clear events from Redis: " + e.getMessage());
        }
    }

    /**
     * Get current event ID from thread local
     */
    public static String getCurrentEventId() {
        return currentEventId.get();
    }

    /**
     * Extract request headers
     */
    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                String headerValue = request.getHeader(headerName);
                headers.put(headerName, headerValue);
            }
        }
        return headers;
    }

    /**
     * Extract response headers
     */
    private Map<String, String> extractResponseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collection<String> headerNames = response.getHeaderNames();
        if (headerNames != null) {
            for (String headerName : headerNames) {
                String headerValue = response.getHeader(headerName);
                headers.put(headerName, headerValue);
            }
        }
        return headers;
    }

    /**
     * Extract request body
     * If body is JSON, also parse and store as key-value map
     */
    private String extractBody(HttpServletRequest request) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = request.getReader();
            if (reader != null) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            String body = sb.length() > 0 ? sb.toString() : "[Empty or already read]";

            // Try to parse as JSON
            if (body != null && !body.startsWith("[")) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> jsonMap = mapper.readValue(body, Map.class);
                    eventData.setRequestBodyJson(jsonMap);
                    System.out.println("[EventHelper] Parsed JSON body with " + jsonMap.size() + " keys");
                } catch (Exception jsonException) {
                    // Not JSON or parse error, just store as string
                    System.out.println("[EventHelper] Body is not valid JSON, storing as string");
                }
            }

            return body;
        } catch (Exception e) {
            return "[Error reading body: " + e.getMessage() + "]";
        }
    }

    /**
     * Extract response body (limited due to response stream already consumed)
     */
    private String extractResponseBody(HttpServletResponse response) {
        try {
            // Note: HttpServletResponse body cannot be read after it's written
            // This is a limitation of the servlet API
            // You would need to use ContentCachingResponseWrapper to capture it
            return "[Response body not available - use ContentCachingResponseWrapper]";
        } catch (Exception e) {
            return "[Error reading response body: " + e.getMessage() + "]";
        }
    }

    private String generateEventId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * EventData - Inner class to store event information
     */
    public static class EventData {
        private String method;
        private String requestUri;
        private String queryString;
        private Map<String, String> requestHeaders;
        private String requestBody;
        private Map<String, Object> requestBodyJson; // Parsed JSON as key-value map
        private int responseStatus;
        private Map<String, String> responseHeaders;
        private String responseBody;
        private Map<String, Object> responseBodyJson; // Parsed response JSON as key-value map
        private long timestamp;

        public EventData() {
            this.requestHeaders = new LinkedHashMap<>();
            this.responseHeaders = new LinkedHashMap<>();
        }

        // Getters and Setters
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getRequestUri() { return requestUri; }
        public void setRequestUri(String requestUri) { this.requestUri = requestUri; }

        public String getQueryString() { return queryString; }
        public void setQueryString(String queryString) { this.queryString = queryString; }

        public Map<String, String> getRequestHeaders() { return requestHeaders; }
        public void setRequestHeaders(Map<String, String> requestHeaders) { this.requestHeaders = requestHeaders; }

        public String getRequestBody() { return requestBody; }
        public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

        public Map<String, Object> getRequestBodyJson() { return requestBodyJson; }
        public void setRequestBodyJson(Map<String, Object> requestBodyJson) { this.requestBodyJson = requestBodyJson; }

        public int getResponseStatus() { return responseStatus; }
        public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }

        public Map<String, String> getResponseHeaders() { return responseHeaders; }
        public void setResponseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; }

        public String getResponseBody() { return responseBody; }
        public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

        public Map<String, Object> getResponseBodyJson() { return responseBodyJson; }
        public void setResponseBodyJson(Map<String, Object> responseBodyJson) { this.responseBodyJson = responseBodyJson; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        /**
         * Convert to Map for JSON serialization (for EventService)
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("method", method);
            map.put("requestUri", requestUri);
            map.put("queryString", queryString != null ? queryString : "");
            map.put("requestHeaders", requestHeaders);
            map.put("requestBody", requestBody != null ? requestBody : "");
            // Include parsed request JSON if available
            if (requestBodyJson != null && !requestBodyJson.isEmpty()) {
                map.put("requestBodyJson", requestBodyJson);
            }
            map.put("responseStatus", responseStatus);
            map.put("responseHeaders", responseHeaders);
            map.put("responseBody", responseBody != null ? responseBody : "");
            // Include parsed response JSON if available
            if (responseBodyJson != null && !responseBodyJson.isEmpty()) {
                map.put("responseBodyJson", responseBodyJson);
            }
            map.put("timestamp", timestamp);
            return map;
        }
    }
}
