package com.socket.context;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

import java.util.Collections;

/**
 * W3C traceparent 형식에서 OpenTelemetry Context를 추출합니다.
 *
 * traceparent 형식: version-traceid-spanid-flags
 * 예: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 */
public class TraceContextExtractor {

    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String W3C_TRACE_PARENT_FORMAT = "00-%s-%s-%02d";

    /**
     * 헤더 맵에서 traceparent를 추출하여 OpenTelemetry Context를 생성합니다.
     *
     * @param headers HTTP 스타일 헤더 맵
     * @return OpenTelemetry Context, 추적 정보가 없으면 root Context 반환
     */
    public static Context extractFromHeaders(java.util.Map<String, String> headers) {
        if (headers == null) {
            return Context.root();
        }

        String traceParent = headers.get(TRACE_PARENT_HEADER);
        if (traceParent == null || traceParent.isEmpty()) {
            return Context.root();
        }

        try {
            return extractFromTraceParent(traceParent);
        } catch (Exception e) {
            System.err.println("Failed to extract trace context: " + e.getMessage());
            return Context.root();
        }
    }

    /**
     * W3C traceparent 문자열에서 OpenTelemetry Context를 생성합니다.
     *
     * @param traceParent traceparent 헤더 값 (예: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
     * @return OpenTelemetry Context
     */
    private static Context extractFromTraceParent(String traceParent) {
        // 형식: version-traceid-spanid-flags
        String[] parts = traceParent.split("-");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid traceparent format: " + traceParent);
        }

        String version = parts[0];
        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];

        // 버전 체크
        if (!"00".equals(version)) {
            throw new IllegalArgumentException("Unsupported traceparent version: " + version);
        }

        // TraceId 유효성 체크 (32자 hex)
        if (traceId.length() != 32) {
            throw new IllegalArgumentException("Invalid trace ID length: " + traceId);
        }

        // SpanId 유효성 체크 (16자 hex)
        if (spanId.length() != 16) {
            throw new IllegalArgumentException("Invalid span ID length: " + spanId);
        }

        // Flags 파싱 (1 byte hex)
        int flagValue = Integer.parseInt(flags, 16);
        byte sampledFlag = (byte) flagValue;

        // SpanContext 생성
        SpanContext spanContext = SpanContext.create(
                traceId,
                spanId,
                TraceFlags.fromByte(sampledFlag),
                TraceState.getDefault()
        );

        // Context에 Span 저장
        return Context.root().with(Span.wrap(spanContext));
    }

    /**
     * 현재 Context에서 traceparent 헤더 문자열을 생성합니다.
     *
     * @return W3C traceparent 형식 문자열
     */
    public static String getCurrentTraceParent() {
        Context currentContext = Context.current();
        Span span = Span.fromContextOrNull(currentContext);

        if (span == null) {
            return null;
        }

        SpanContext spanContext = span.getSpanContext();
        if (!spanContext.isValid()) {
            return null;
        }

        return String.format(W3C_TRACE_PARENT_FORMAT,
                spanContext.getTraceId(),
                spanContext.getSpanId(),
                spanContext.getTraceFlags().asByte() & 0xFF
        );
    }

    /**
     * 현재 Context의 trace ID를 반환합니다.
     *
     * @return trace ID, 유효한 context가 없으면 "unknown"
     */
    public static String getCurrentTraceId() {
        Context currentContext = Context.current();
        Span span = Span.fromContextOrNull(currentContext);

        if (span == null) {
            return "unknown";
        }

        SpanContext spanContext = span.getSpanContext();
        if (!spanContext.isValid()) {
            return "unknown";
        }

        return spanContext.getTraceId();
    }

    /**
     * 현재 Context의 span ID를 반환합니다.
     *
     * @return span ID, 유효한 context가 없으면 "unknown"
     */
    public static String getCurrentSpanId() {
        Context currentContext = Context.current();
        Span span = Span.fromContextOrNull(currentContext);

        if (span == null) {
            return "unknown";
        }

        SpanContext spanContext = span.getSpanContext();
        if (!spanContext.isValid()) {
            return "unknown";
        }

        return spanContext.getSpanId();
    }
}
