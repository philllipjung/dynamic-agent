package com.agent.advices;

import com.agent.helper.SpanCreateHelper;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;
import java.lang.reflect.Method;

public class SpanCreateAdvice {
    private static final ThreadLocal<SpanCreateHelper> helperHolder = new ThreadLocal<>();
    private static final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();
    private static final ThreadLocal<Scope> scopeHolder = new ThreadLocal<>();

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onMethodEnter(@Advice.Origin Method method, @Advice.This Object target) {
        try {
            String className = target.getClass().getName();
            String methodName = method != null ? method.getName() : "unknown";
            Tracer tracer = getTracer();
            if (tracer == null) return;
            startTimeHolder.set(System.nanoTime());
            io.opentelemetry.api.trace.Span span = tracer.spanBuilder(className + "." + methodName)
                    .setParent(io.opentelemetry.context.Context.current())
                    .startSpan();
            Scope scope = span.makeCurrent();
            scopeHolder.set(scope);
            SpanCreateHelper helper = new SpanCreateHelper(span);
            helper.setMethodAttributes(className, methodName, 0L);
            helperHolder.set(helper);
        } catch (Exception e) {
            System.err.println("[SpanCreateAdvice] onMethodEnter error: " + e.getMessage());
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMethodExit(@Advice.Thrown Throwable throwable) {
        try {
            SpanCreateHelper helper = helperHolder.get();
            if (helper == null) return;
            Long startTime = startTimeHolder.get();
            long durationMs = (startTime != null) ? (System.nanoTime() - startTime) / 1_000_000 : 0;
            helper.setDuration(durationMs);
            if (throwable != null) helper.recordException(throwable);
            else helper.complete();
        } catch (Exception e) {
            System.err.println("[SpanCreateAdvice] onMethodExit error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private static void cleanup() {
        Scope scope = scopeHolder.get();
        if (scope != null) scope.close();
        scopeHolder.remove();
        helperHolder.remove();
        startTimeHolder.remove();
    }

    private static Tracer getTracer() {
        try {
            return io.opentelemetry.api.GlobalOpenTelemetry.getTracer("SpanCreateAdvice", "1.0.0");
        } catch (Exception e) {
            return null;
        }
    }
}
