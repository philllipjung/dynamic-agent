package com.javaagent.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * OtelHelper - OpenTelemetry Helper Class (Minimal version)
 *
 * This class provides a bridge between ByteBuddy advice and the OpenTelemetry API.
 *
 * Requirements:
 * - OpenTelemetry Java agent must be attached to the JVM
 * - OpenTelemetry API must be on classpath (compileOnly dependency)
 */
public class OtelHelper {

    /**
     * Set an attribute on a span
     *
     * @param span The span to modify
     * @param key Attribute key (e.g., "user.id", "session.id")
     * @param value Attribute value
     */
    public static void setAttribute(Span span, String key, String value) {
        if (span != null && key != null && value != null) {
            span.setAttribute(key, value);
        }
    }

    /**
     * Get an attribute from a span
     * NOTE: OTEL Span API doesn't support reading attributes at runtime
     *
     * @param span The span to read from
     * @param key Attribute key
     * @return Always null (not supported by OTEL API)
     */
    public static String getAttribute(Span span, String key) {
        // OTEL Span doesn't expose attributes for reading
        return null;
    }

    /**
     * Add a log event to a span
     *
     * @param span The span to add event to
     * @param message Log message
     */
    public static void logToSpan(Span span, String message) {
        if (span != null && message != null) {
            span.addEvent(message);
        }
    }

    /**
     * Get the current OpenTelemetry context
     *
     * @return Current Context
     */
    public static Context currentContext() {
        return Context.current();
    }

    /**
     * Get the current span from context
     *
     * @return Current Span (may be invalid if no span is active)
     */
    public static Span currentSpan() {
        return Span.current();
    }
}
