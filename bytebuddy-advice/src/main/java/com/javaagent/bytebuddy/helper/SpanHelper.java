package com.javaagent.bytebuddy.helper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.List;

public class SpanHelper {

    private static Tracer tracer = null;

    public static void setTracer(Tracer t) {
        tracer = t;
    }

    public static SpanHelper createSpan(String methodName) {
        return createSpanWithLinks(methodName, null);
    }

    /**
     * Links를 포함하여 Span 생성
     *
     * @param methodName 메서드명
     * @param linkedContexts 연결할 SpanContext 목록 (null 가능)
     * @return SpanHelper
     */
    public static SpanHelper createSpanWithLinks(String methodName, List<SpanContext> linkedContexts) {
        if (tracer == null) {
            System.err.println("[SpanHelper] Tracer not initialized!");
            return null;
        }

        // SpanBuilder 생성
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(methodName);

        // Links 추가
        if (linkedContexts != null && !linkedContexts.isEmpty()) {
            for (SpanContext context : linkedContexts) {
                builder.addLink(context);
                System.out.println("[SpanHelper] Added link to span: "
                    + context.getTraceId() + "/" + context.getSpanId());
            }
        }

        // Span 시작
        Span span = builder.startSpan();

        // CRITICAL: Make this span the current span in context
        // This allows SpanAttributeAdvice to access it via Span.current()
        Scope scope = span.makeCurrent();
        System.out.println("[SpanHelper] Span created and made current: " + methodName);
        System.out.println("[SpanHelper] Span.current() == span: " + (Span.current() == span));

        return new SpanHelper(span, scope);
    }

    private final Span span;
    private final Scope scope; // Scope to close when completing span

    public SpanHelper(Span span, Scope scope) {
        this.span = span;
        this.scope = scope;
    }

    // Legacy constructor for backward compatibility
    public SpanHelper(Span span) {
        this.span = span;
        this.scope = null;
    }

    public SpanHelper addAttribute(String key, String value) {
        if (span != null && key != null && value != null) {
            span.setAttribute(key, value);
        }
        return this;
    }

    public SpanHelper addAttribute(String key, long value) {
        if (span != null && key != null) {
            span.setAttribute(key, value);
        }
        return this;
    }

    public SpanHelper addAttribute(String key, double value) {
        if (span != null && key != null) {
            span.setAttribute(key, value);
        }
        return this;
    }

    public SpanHelper addAttribute(String key, boolean value) {
        if (span != null && key != null) {
            span.setAttribute(key, value);
        }
        return this;
    }

    public SpanHelper setDuration(long durationMs) {
        return addAttribute("method.duration_ms", durationMs);
    }

    public SpanHelper setMethodAttributes(String className, String methodName, long durationMs) {
        addAttribute("method.name", methodName != null ? methodName : "unknown");
        addAttribute("method.class", className != null ? className : "unknown");
        addAttribute("code.namespace", className != null ? className : "unknown");
        addAttribute("code.function", methodName != null ? methodName : "unknown");
        if (className != null && methodName != null) {
            addAttribute("method.full", className + "." + methodName);
        }
        if (durationMs > 0) {
            setDuration(durationMs);
        }
        return this;
    }

    public SpanHelper recordException(Throwable throwable) {
        if (span != null && throwable != null) {
            span.recordException(throwable);
            setError("Exception: " + throwable.getMessage());
        }
        return this;
    }

    public SpanHelper setError(String description) {
        if (span != null) {
            span.setStatus(StatusCode.ERROR, description);
        }
        return this;
    }

    public void complete() {
        if (span != null) {
            span.setStatus(StatusCode.OK);
            span.end();
        }
        // Close scope to remove span from current context
        if (scope != null) {
            scope.close();
        }
    }

    public Span getSpan() {
        return span;
    }

    public boolean isValid() {
        return span != null;
    }

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * byte[]를 hex string으로 변환
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * hex string을 byte[]로 변환
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
}
