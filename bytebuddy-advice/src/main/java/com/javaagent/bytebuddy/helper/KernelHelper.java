package com.javaagent.bytebuddy.helper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicReference;

/**
 * KernelHelper - Manages OpenTelemetry spans and kernel-level tracing operations
 *
 * Features:
 * - Automatic span creation and lifecycle management
 * - TraceId/SpanId extraction and formatting
 * - Thread name customization with tracing info
 * - Child span creation for parallel/async tasks
 * - Reactor scheduler integration (parallel, boundedElastic)
 *
 * Usage:
 * <pre>
 * // Create helper with span
 * KernelHelper helper = new KernelHelper(tracer, "operation-name").startSpan();
 *
 * // Get trace/span info
 * String traceId = helper.getTraceId();
 * String spanId = helper.getSpanId();
 *
 * // Customize thread name
 * helper.setThreadName("custom-prefix");
 *
 * // Create child span for parallel tasks
 * KernelHelper childHelper = helper.createChildSpan("parallel-task-1");
 *
 * // Complete span
 * helper.complete();
 * </pre>
 */
public class KernelHelper {
    private final Tracer tracer;
    private Span span;
    private Scope scope;
    private String operationName;
    private final AtomicReference<SpanContext> spanContextRef = new AtomicReference<>();

    /**
     * Create KernelHelper with tracer and operation name
     */
    public KernelHelper(Tracer tracer, String operationName) {
        this.tracer = tracer;
        this.operationName = operationName;
    }

    /**
     * Start span with default configuration
     */
    public KernelHelper startSpan() {
        if (tracer != null) {
            span = tracer.spanBuilder(operationName)
                    .startSpan();
            scope = span.makeCurrent();
            spanContextRef.set(span.getSpanContext());

            logSpanInfo();
        }
        return this;
    }

    /**
     * Start span with parent context
     */
    public KernelHelper startSpan(io.opentelemetry.context.Context parentContext) {
        if (tracer != null) {
            span = tracer.spanBuilder(operationName)
                    .setParent(parentContext)
                    .startSpan();
            scope = span.makeCurrent();
            spanContextRef.set(span.getSpanContext());

            logSpanInfo();
        }
        return this;
    }

    /**
     * Create a child span for parallel/async tasks
     * Inherits trace ID from parent, gets new span ID
     */
    public KernelHelper createChildSpan(String childOperationName) {
        KernelHelper childHelper = new KernelHelper(tracer, childOperationName);

        if (span != null) {
            // Child span inherits from current parent span
            io.opentelemetry.context.Context parentContext = span.storeInContext(io.opentelemetry.context.Context.current());
            childHelper.startSpan(parentContext);
        } else {
            childHelper.startSpan();
        }

        return childHelper;
    }

    /**
     * Get TraceId from current span
     */
    public String getTraceId() {
        SpanContext context = spanContextRef.get();
        if (context != null && context.isValid()) {
            return context.getTraceId();
        }
        return "unknown-trace-id";
    }

    /**
     * Get SpanId from current span
     */
    public String getSpanId() {
        SpanContext context = spanContextRef.get();
        if (context != null && context.isValid()) {
            return context.getSpanId();
        }
        return "unknown-span-id";
    }

    /**
     * Get formatted thread name with trace/span info
     * New Format: {TraceId 7}{SpanId 6}{Initial}{Number}
     * Example: 1234abcd5678efgR1
     */
    public String getCustomThreadName(String prefix, String suffix) {
        String traceId = getTraceId();
        String spanId = getSpanId();
        String initial = getInitial(prefix);

        // TraceId: 앞 7자리
        String shortTraceId = traceId.length() >= 7 ? traceId.substring(0, 7) : traceId;

        // SpanId: 앞 6자리
        String shortSpanId = spanId.length() >= 6 ? spanId.substring(0, 6) : spanId;

        // Format: {TraceId 7}{SpanId 6}{Initial}{Number}
        return shortTraceId + shortSpanId + initial + suffix;
    }

    /**
     * Extract initial from thread type prefix
     * reactor-http-epoll → R
     * parallel → P
     * boundedElastic → B
     */
    private String getInitial(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "X";
        }
        // 첫 글자만 추출 (대문자로)
        return String.valueOf(Character.toUpperCase(prefix.charAt(0)));
    }

    /**
     * Set current thread name with trace/span info
     */
    public KernelHelper setThreadName(String prefix, String suffix) {
        String customName = getCustomThreadName(prefix, suffix);
        Thread.currentThread().setName(customName);
        System.out.println("[KernelHelper] Thread name set to: " + customName);
        return this;
    }

    /**
     * Set current thread name for reactor-http-epoll threads
     * Format: {TraceId 7}{SpanId 6}R{threadNumber}
     * Example: 1234abcd5678efgR1
     */
    public KernelHelper setReactorHttpEpollThreadName() {
        String originalThreadName = Thread.currentThread().getName();
        if (originalThreadName.contains("reactor-http-epoll")) {
            String threadNumber = originalThreadName.substring(originalThreadName.lastIndexOf("-") + 1);
            return setThreadName("reactor-http-epoll", threadNumber);
        }
        return this;
    }

    /**
     * Set current thread name for parallel tasks
     * Format: {TraceId 7}{SpanId 6}P{taskNumber}
     * Example: 1234abcd5678efgP1
     */
    public KernelHelper setParallelThreadName(int taskNumber) {
        return setThreadName("parallel", String.valueOf(taskNumber));
    }

    /**
     * Set current thread name for boundedElastic tasks
     * Format: {TraceId 7}{SpanId 6}B{taskNumber}
     * Example: 1234abcd5678efgB1
     */
    public KernelHelper setBoundedElasticThreadName(int taskNumber) {
        return setThreadName("boundedElastic", String.valueOf(taskNumber));
    }

    /**
     * Complete the current span
     */
    public void complete() {
        if (scope != null) {
            scope.close();
            scope = null;
        }
        if (span != null) {
            span.end();
            System.out.println("[KernelHelper] Span completed: " + operationName +
                    " (TraceId: " + getTraceId() + ", SpanId: " + getSpanId() + ")");
            span = null;
        }
        spanContextRef.set(null);
    }

    /**
     * Get the underlying span
     */
    public Span getSpan() {
        return span;
    }

    /**
     * Check if span is valid
     */
    public boolean isValid() {
        return span != null && spanContextRef.get() != null && spanContextRef.get().isValid();
    }

    /**
     * Record exception in span
     */
    public KernelHelper recordException(Throwable throwable) {
        if (span != null) {
            span.recordException(throwable);
        }
        return this;
    }

    /**
     * Set attribute on span
     */
    public KernelHelper setAttribute(String key, String value) {
        if (span != null) {
            span.setAttribute(key, value);
        }
        return this;
    }

    /**
     * Set event name
     */
    public KernelHelper setOperationName(String operationName) {
        this.operationName = operationName;
        return this;
    }

    /**
     * Log span information
     */
    private void logSpanInfo() {
        if (isValid()) {
            System.out.println("[KernelHelper] Span created - TraceId: " + getTraceId() +
                    ", SpanId: " + getSpanId() +
                    ", Operation: " + operationName +
                    ", Thread: " + Thread.currentThread().getName());
        }
    }

    /**
     * Factory method to create helper with auto-started span
     */
    public static KernelHelper create(Tracer tracer, String operationName) {
        return new KernelHelper(tracer, operationName).startSpan();
    }

    /**
     * Factory method to create helper with parent context
     */
    public static KernelHelper createWithParent(Tracer tracer, String operationName, io.opentelemetry.context.Context parentContext) {
        return new KernelHelper(tracer, operationName).startSpan(parentContext);
    }

    /**
     * Get current span from OpenTelemetry context
     */
    public static Span getCurrentSpan() {
        return Span.current();
    }

    /**
     * Get current trace ID
     */
    public static String getCurrentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context != null && context.isValid() ? context.getTraceId() : "unknown";
    }

    /**
     * Get current span ID
     */
    public static String getCurrentSpanId() {
        SpanContext context = Span.current().getSpanContext();
        return context != null && context.isValid() ? context.getSpanId() : "unknown";
    }
}
