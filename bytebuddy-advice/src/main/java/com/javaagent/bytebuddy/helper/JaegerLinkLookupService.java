package com.javaagent.bytebuddy.helper;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jaeger API를 사용하여 span link를 위한 target span 검색
 *
 * userId를 기준으로 Jaeger에서 trace를 조회하여
 * 같은 userId를 가진 span의 SpanContext를 반환
 */
public class JaegerLinkLookupService {

    private static final String JAEGER_API_URL = "http://localhost:16686";
    private static final int CONNECT_TIMEOUT = 5000;  // 5초
    private static final int READ_TIMEOUT = 5000;     // 5초

    /**
     * userId에 해당하는 target span의 SpanContext 찾기
     *
     * @param targetService 대상 서비스명 (예: "unknown_service:java")
     * @param targetOperationName 대상 operation명 (전체 시그니처)
     * @param userId 사용자 ID
     * @return 발견된 SpanContext 목록
     */
    public static List<SpanContext> findTargetSpanContexts(
        String targetService,
        String targetOperationName,
        String userId
    ) {
        List<SpanContext> contexts = new ArrayList<>();
        Set<String> seenSpanKeys = new HashSet<>();  // 전역 중복 방지

        try {
            // 1. Jaeger API 호출
            String apiUrl = buildJaegerQueryUrl(targetService, userId);
            System.out.println("[JaegerLink] Querying: " + apiUrl);

            String jsonResponse = executeGetRequest(apiUrl);

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                System.out.println("[JaegerLink] No response from Jaeger");
                return contexts;
            }

            // 2. JSON 파싱하여 TraceID와 SpanID 추출
            List<SpanRef> spans = parseJaegerResponse(jsonResponse, targetOperationName, seenSpanKeys);

            // 3. SpanContext 생성
            for (SpanRef spanRef : spans) {
                try {
                    SpanContext context = createSpanContext(spanRef.traceID, spanRef.spanID);
                    contexts.add(context);
                    System.out.println("[JaegerLink] Found span - traceID: " + spanRef.traceID
                        + ", spanID: " + spanRef.spanID);
                } catch (Exception e) {
                    System.err.println("[JaegerLink] Error creating context: " + e.getMessage());
                }
            }

            System.out.println("[JaegerLink] Found " + contexts.size() + " matching spans");

        } catch (Exception e) {
            System.err.println("[JaegerLink] Error finding spans: " + e.getMessage());
            e.printStackTrace();
        }

        return contexts;
    }

    /**
     * Jaeger API URL 빌드
     */
    private static String buildJaegerQueryUrl(String service, String userId) {
        try {
            // Jaeger API tags 형식: key:value (콜론으로 구분)
            return JAEGER_API_URL + "/api/traces"
                + "?service=" + URLEncoder.encode(service, StandardCharsets.UTF_8)
                + "&tag=arthas.attribute.userId:" + URLEncoder.encode(userId, StandardCharsets.UTF_8)
                + "&limit=10";
        } catch (Exception e) {
            throw new RuntimeException("Error building URL", e);
        }
    }

    /**
     * HTTP GET 요청 실행
     */
    private static String executeGetRequest(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                System.err.println("[JaegerLink] HTTP error: " + responseCode);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            );

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            return response.toString();

        } catch (Exception e) {
            System.err.println("[JaegerLink] Request failed: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Jaeger 응답 JSON 파싱 (간단한 정규식 사용)
     *
     * 실제 프로덕션에서는 Jackson/Gson 사용 권장
     */
    private static List<SpanRef> parseJaegerResponse(String jsonResponse, String targetOperationName, Set<String> seenSpanKeys) {
        List<SpanRef> spans = new ArrayList<>();

        try {
            // 각 span 객체를 개별적으로 파싱하여 spanID와 operationName 쌍을 추출
            Pattern spanPattern = Pattern.compile("\\{[^}]*\"spanID\"\\s*:\\s*\"([0-9a-fA-F]+)\"[^}]*\"operationName\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}");
            // 위 패턴은 spanID와 operationName이 같은 객체 내에 있는 경우만 매칭

            // 더 유연한 접근: span 객체 경계를 찾고 그 안에서 필드 추출
            Pattern spanObjectPattern = Pattern.compile("\\{[^}]*\"spanID\"[^}]*\"operationName\"[^}]*\\}");
            Pattern spanIdPattern = Pattern.compile("\"spanID\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
            Pattern opNamePattern = Pattern.compile("\"operationName\"\\s*:\\s*\"([^\"]+)\"");

            Matcher spanObjectMatcher = spanObjectPattern.matcher(jsonResponse);

            while (spanObjectMatcher.find()) {
                String spanObject = spanObjectMatcher.group();

                // 이 span 객체 안에서 spanID와 operationName 추출
                Matcher spanIdMatcher = spanIdPattern.matcher(spanObject);
                Matcher opNameMatcher = opNamePattern.matcher(spanObject);

                if (spanIdMatcher.find() && opNameMatcher.find()) {
                    String spanId = spanIdMatcher.group(1);
                    String operationName = opNameMatcher.group(1);

                    // 타겟 operationName과 매칭 확인
                    if (targetOperationName.equals(operationName)) {
                        // traceID 추출을 위해 앞 부분에서 traceID 찾기
                        int spanStart = spanObjectMatcher.start();
                        String beforeSpan = jsonResponse.substring(0, spanStart);

                        // 가장 가까운 traceID 찾기
                        Pattern tracePattern = Pattern.compile("\"traceID\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
                        Matcher traceMatcher = tracePattern.matcher(beforeSpan);

                        String traceId = null;
                        while (traceMatcher.find()) {
                            traceId = traceMatcher.group(1);
                        }

                        if (traceId != null) {
                            // 중복 방지
                            String uniqueKey = traceId + ":" + spanId;
                            if (!seenSpanKeys.contains(uniqueKey)) {
                                seenSpanKeys.add(uniqueKey);
                                spans.add(new SpanRef(traceId, spanId));
                                System.out.println("[JaegerLink] Matched span: traceID=" + traceId + ", spanID=" + spanId + ", operation=" + operationName);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[JaegerLink] JSON parse error: " + e.getMessage());
            e.printStackTrace();
        }

        return spans;
    }

    /**
     * trace 객체의 끝 위치 찾기
     */
    private static int findEndOfTraceObject(String json, int start) {
        // "spans" 배열의 끝을 찾기
        int spansIndex = json.indexOf("\"spans\"", start);
        if (spansIndex == -1) {
            return json.length();
        }

        // spans 배열 시작 ([) 찾기
        int arrayStart = json.indexOf('[', spansIndex);
        if (arrayStart == -1) {
            return json.length();
        }

        // 해당하는 닫는 괄호 ] 찾기
        int depth = 0;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }

        return json.length();
    }

    /**
     * Hex string을 byte[]로 변환
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * traceID와 spanID로 SpanContext 생성
     *
     * SpanContext를 직접 구현한 간단한 클래스 사용
     */
    public static SpanContext createSpanContext(String traceID, String spanID) {
        System.out.println("[JaegerLink] Creating SpanContext: traceID=" + traceID + " (len=" + traceID.length() + "), spanID=" + spanID + " (len=" + spanID.length() + ")");

        byte[] traceIdBytes = hexToBytes(traceID);
        byte[] spanIdBytes = hexToBytes(spanID);

        System.out.println("[JaegerLink] traceIdBytes length: " + traceIdBytes.length + ", spanIdBytes length: " + spanIdBytes.length);

        // traceID는 16바이트(128비트), spanID는 8바이트(64비트)여야 함
        if (traceIdBytes.length != 16) {
            System.err.println("[JaegerLink] Invalid traceID length: " + traceIdBytes.length + " (expected 16)");
        }
        if (spanIdBytes.length != 8) {
            System.err.println("[JaegerLink] Invalid spanID length: " + spanIdBytes.length + " (expected 8)");
        }

        // Simple SpanContext implementation
        return new SimpleSpanContext(traceID, spanID, traceIdBytes, spanIdBytes);
    }

    /**
     * Simple SpanContext implementation for link creation
     */
    private static class SimpleSpanContext implements SpanContext {
        private final String traceId;
        private final String spanId;
        private final byte[] traceIdBytes;
        private final byte[] spanIdBytes;
        private final TraceFlags traceFlags;
        private final TraceState traceState;

        SimpleSpanContext(String traceId, String spanId, byte[] traceIdBytes, byte[] spanIdBytes) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.traceIdBytes = traceIdBytes;
            this.spanIdBytes = spanIdBytes;
            this.traceFlags = TraceFlags.getDefault();
            this.traceState = TraceState.getDefault();
        }

        @Override
        public String getTraceId() {
            return traceId;
        }

        @Override
        public String getSpanId() {
            return spanId;
        }

        @Override
        public TraceFlags getTraceFlags() {
            return traceFlags;
        }

        @Override
        public TraceState getTraceState() {
            return traceState;
        }

        @Override
        public boolean isValid() {
            return traceId != null && !traceId.isEmpty()
                && spanId != null && !spanId.isEmpty()
                && traceIdBytes.length == 16
                && spanIdBytes.length == 8;
        }

        @Override
        public boolean isRemote() {
            return false;
        }
    }

    /**
     * Span 참조 (traceID, spanID 쌍)
     */
    private static class SpanRef {
        final String traceID;
        final String spanID;

        SpanRef(String traceID, String spanID) {
            this.traceID = traceID;
            this.spanID = spanID;
        }
    }
}
