package com.javaagent.bytebuddy.advices;

import com.javaagent.bytebuddy.helper.KernelHelper;
import io.opentelemetry.api.trace.Tracer;
import net.bytebuddy.asm.Advice;
import java.lang.reflect.Method;

/**
 * KernelAdvice - Automatic kernel-level tracing for any method
 *
 * Features:
 * - Automatic span creation on method entry
 * - TraceId/SpanId extraction
 * - Automatic thread name customization with tracing info
 * - Child span support for parallel/async operations
 * - Automatic span completion on method exit
 *
 * Usage:
 * Apply to any method to automatically enable kernel-level tracing:
 * <pre>
 * // For controller methods
 * POST /api/bytebuddy/createKernelAdvice
 * {
 *   "className": "com.example.demo.Service1Controller",
 *   "methodName": "index"
 * }
 * </pre>
 *
 * The advice will:
 * 1. Create span on method entry
 * 2. Customize thread name with trace/span info
 * 3. Complete span on method exit
 */
public class KernelAdvice {
    private static final ThreadLocal<KernelHelper> helperHolder = new ThreadLocal<>();
    private static final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();

    /**
     * Thread-local storage for parallel task numbering
     */
    private static final ThreadLocal<Integer> parallelTaskNumber = new ThreadLocal<>();

    /**
     * On method enter - create span and customize thread name
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onMethodEnter(
            @Advice.Origin Method method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] allArguments
    ) {
        try {
            String className = target.getClass().getSimpleName();
            String methodName = method != null ? method.getName() : "unknown";
            String operationName = className + "." + methodName;

            Tracer tracer = getTracer();
            if (tracer == null) {
                System.err.println("[KernelAdvice] Tracer not available");
                return;
            }

            startTimeHolder.set(System.nanoTime());

            // Create KernelHelper and start span
            KernelHelper helper = new KernelHelper(tracer, operationName).startSpan();

            // Customize thread name based on thread type
            customizeThreadName(helper, className, methodName);

            // Log method arguments
            logMethodArguments(allArguments);

            // Store helper in thread-local
            helperHolder.set(helper);

        } catch (Exception e) {
            System.err.println("[KernelAdvice] Error in onMethodEnter: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * On method exit - complete span
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMethodExit(
            @Advice.Thrown Throwable throwable
    ) {
        try {
            KernelHelper helper = helperHolder.get();
            if (helper == null) {
                return;
            }

            // Calculate duration
            Long startTime = startTimeHolder.get();
            long durationMs = (startTime != null) ? (System.nanoTime() - startTime) / 1_000_000 : 0;

            // Set duration attribute
            helper.setAttribute("method.duration.ms", String.valueOf(durationMs));

            // Record exception if thrown
            if (throwable != null) {
                helper.recordException(throwable);
                System.err.println("[KernelAdvice] Method threw exception: " + throwable.getMessage());
            }

            // Complete span
            helper.complete();

            System.out.println("[KernelAdvice] Method completed in " + durationMs + "ms");

        } catch (Exception e) {
            System.err.println("[KernelAdvice] Error in onMethodExit: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    /**
     * Customize thread name based on thread type and operation
     */
    private static void customizeThreadName(KernelHelper helper, String className, String methodName) {
        String threadName = Thread.currentThread().getName();

        // Reactor HTTP epoll threads
        if (threadName.contains("reactor-http-epoll")) {
            String threadNumber = threadName.substring(threadName.lastIndexOf("-") + 1);
            helper.setThreadName("reactor-http-epoll", threadNumber);
        }
        // Parallel scheduler threads
        else if (threadName.contains("parallel")) {
            int taskNum = getNextParallelTaskNumber();
            helper.setParallelThreadName(taskNum);
        }
        // BoundedElastic scheduler threads
        else if (threadName.contains("boundedElastic")) {
            int taskNum = getNextParallelTaskNumber();
            helper.setBoundedElasticThreadName(taskNum);
        }
        // Default: use class and method name
        else {
            helper.setThreadName(className, methodName);
        }
    }

    /**
     * Get next parallel task number
     */
    private static int getNextParallelTaskNumber() {
        Integer current = parallelTaskNumber.get();
        if (current == null) {
            current = 0;
        }
        parallelTaskNumber.set(current + 1);
        return current;
    }

    /**
     * Log method arguments
     */
    private static void logMethodArguments(Object[] allArguments) {
        if (allArguments != null && allArguments.length > 0) {
            StringBuilder args = new StringBuilder("[");
            for (int i = 0; i < allArguments.length; i++) {
                if (i > 0) args.append(", ");
                Object arg = allArguments[i];
                args.append(arg != null ? arg.getClass().getSimpleName() : "null");
                if (arg instanceof String) {
                    args.append("=").append(arg);
                }
            }
            args.append("]");
            System.out.println("[KernelAdvice] Method arguments: " + args);
        }
    }

    /**
     * Clean up thread-local storage
     */
    private static void cleanup() {
        KernelHelper helper = helperHolder.get();
        if (helper != null) {
            helper.complete();
        }
        helperHolder.remove();
        startTimeHolder.remove();
        parallelTaskNumber.remove();
    }

    /**
     * Get tracer from OpenTelemetry
     */
    private static Tracer getTracer() {
        try {
            return io.opentelemetry.api.GlobalOpenTelemetry.getTracer("KernelAdvice", "1.0.0");
        } catch (Exception e) {
            System.err.println("[KernelAdvice] Failed to get tracer: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get current KernelHelper for this thread
     */
    public static KernelHelper getCurrentHelper() {
        return helperHolder.get();
    }

    /**
     * Create child span for parallel/async operations
     */
    public static KernelHelper createChildSpan(String childOperationName) {
        KernelHelper parentHelper = helperHolder.get();
        if (parentHelper != null && parentHelper.isValid()) {
            return parentHelper.createChildSpan(childOperationName);
        }
        return null;
    }

    /**
     * Get current trace ID
     */
    public static String getCurrentTraceId() {
        KernelHelper helper = helperHolder.get();
        if (helper != null) {
            return helper.getTraceId();
        }
        return KernelHelper.getCurrentTraceId();
    }

    /**
     * Get current span ID
     */
    public static String getCurrentSpanId() {
        KernelHelper helper = helperHolder.get();
        if (helper != null) {
            return helper.getSpanId();
        }
        return KernelHelper.getCurrentSpanId();
    }
}
