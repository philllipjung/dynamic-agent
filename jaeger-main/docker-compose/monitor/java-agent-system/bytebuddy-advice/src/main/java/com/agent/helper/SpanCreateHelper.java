package com.agent.helper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

public class SpanCreateHelper {
    private final Span span;

    public SpanCreateHelper(Span span) {
        this.span = span;
    }

    public SpanCreateHelper addAttribute(String key, String value) {
        if (span != null && key != null && value != null) {
            span.setAttribute(key, value);
        }
        return this;
    }

    public SpanCreateHelper setDuration(long durationMs) {
        return addAttribute("method.duration_ms", durationMs);
    }

    public SpanCreateHelper setMethodAttributes(String className, String methodName, long durationMs) {
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

    public SpanCreateHelper recordException(Throwable throwable) {
        if (span != null && throwable != null) {
            span.recordException(throwable);
            setError("Exception: " + throwable.getMessage());
        }
        return this;
    }

    public SpanCreateHelper setError(String description) {
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
