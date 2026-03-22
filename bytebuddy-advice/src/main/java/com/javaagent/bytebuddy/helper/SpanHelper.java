package com.javaagent.bytebuddy.helper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

public class SpanHelper {
    private final Span span;

    public SpanHelper(Span span) {
        this.span = span;
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
    }

    public Span getSpan() {
        return span;
    }

    public boolean isValid() {
        return span != null;
    }
}
