package com.agent.helper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

public class SpanAttributeHelper {
    private static final String ATTRIBUTE_PREFIX = "arthas.attribute.";
    private final Span span;

    public SpanAttributeHelper(Span span) {
        this.span = span;
    }

    public SpanAttributeHelper setBasicAttributes(String className, String methodName, long javaThreadId, long linuxThreadId, long pid) {
        setMethodAttribute(className, methodName);
        setAttribute("thread.id", javaThreadId);
        setAttribute("linux.thread.id", linuxThreadId);
        setAttribute("pid", pid);
        return this;
    }

    public SpanAttributeHelper setMethodAttribute(String className, String methodName) {
        if (methodName != null && !methodName.isEmpty()) {
            setAttribute("method", methodName);
        }
        if (className != null && methodName != null) {
            setAttribute("full", className + "." + methodName);
        }
        return this;
    }

    public SpanAttributeHelper setParameterAttribute(String paramName, Object paramValue) {
        if (paramName == null || paramName.isEmpty()) {
            return this;
        }
        String value = convertToString(paramValue);
        setAttribute(paramName, value);
        return this;
    }

    public SpanAttributeHelper setAttribute(String key, String value) {
        if (span != null && key != null && value != null) {
            String fullKey = ATTRIBUTE_PREFIX + key;
            span.setAttribute(fullKey, value);
        }
        return this;
    }

    public SpanAttributeHelper setAttribute(String key, long value) {
        if (span != null && key != null) {
            String fullKey = ATTRIBUTE_PREFIX + key;
            span.setAttribute(fullKey, value);
        }
        return this;
    }

    public SpanAttributeHelper recordException(Throwable throwable) {
        if (span != null && throwable != null) {
            span.recordException(throwable);
            setError("Exception: " + throwable.getMessage());
        }
        return this;
    }

    public SpanAttributeHelper setError(String description) {
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

    private String convertToString(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return (String) obj;
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        return obj.toString();
    }
}
