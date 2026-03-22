package com.javaagent.bytebuddy.helper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.HashMap;

/**
 * Span Link Helper - OpenTelemetry Helper for Span Linking
 *
 * Based on OtelHelper.java.template pattern
 * Adds span linking functionality to connect separate traces
 */
public class SpanLinkHelper {

    private final Span span;
    private final Map<String, String> capturedAttributes;
    private Scope scope;

    private static final String ATTRIBUTE_PREFIX = "arthas.attribute.";

    public SpanLinkHelper(Span span) {
        this.span = span;
        this.capturedAttributes = new HashMap<>();
    }

    /**
     * Make span current
     */
    public void makeCurrent() {
        if (span != null && span.isRecording()) {
            scope = span.makeCurrent();
        }
    }

    /**
     * Close scope
     */
    public void closeScope() {
        if (scope != null) {
            scope.close();
            scope = null;
        }
    }

    // ============================================================
    // Template 기본 메서드
    // ============================================================

    public void setAttribute(String key, String value) {
        if (span != null && key != null && value != null) {
            span.setAttribute(key, value);
            capturedAttributes.put(key, value);
        }
    }

    public String getAttribute(String key) {
        return capturedAttributes.get(key);
    }

    // ============================================================
    // 스팬 링크 전용 메서드
    // ============================================================

    /**
     * 스팬 링크 추가 (Attribute로 저장)
     *
     * OpenTelemetry에서 링크는 SpanBuilder에서만 추가 가능하므로
     * 이미 시작된 스팬에는 attribute로 링크 정보 저장
     *
     * @param traceId 타겟 Trace ID
     * @param spanId 타겟 Span ID
     * @param linkType 링크 타입
     */
    public void addSpanLink(String traceId, String spanId, String linkType) {
        try {
            // 링크 정보를 attribute로 저장
            String linkKey = "link." + linkType.toLowerCase() + "." + traceId;
            setAttribute(linkKey, spanId);

            System.out.println("[SpanLinkHelper] Added link attribute: " + linkKey + " -> " + spanId);

        } catch (Exception e) {
            System.err.println("[SpanLinkHelper] Error adding link: " + e.getMessage());
        }
    }

    // ============================================================
    // 기존 Helper 호환
    // ============================================================

    public void setBasicAttributes(String className, String methodName, long threadId, long linuxTid, long pid) {
        setAttribute("method.name", methodName);
        setAttribute("method.class", className);
        setAttribute("code.namespace", className);
        setAttribute("code.function", methodName);
        setAttribute("thread.id", String.valueOf(threadId));
        setAttribute("linux.thread.id", String.valueOf(linuxTid));
        setAttribute("process.id", String.valueOf(pid));
    }

    public void setParameterAttribute(String paramName, Object paramValue) {
        String key = ATTRIBUTE_PREFIX + paramName;
        String value = paramValue == null ? "null" : paramValue.toString();
        setAttribute(key, value);
    }

    public void recordException(Throwable throwable) {
        if (span != null && throwable != null) {
            span.recordException(throwable);
        }
    }

    public void complete() {
        closeScope();
        if (span != null && span.isRecording()) {
            span.end();
        }
    }
}
