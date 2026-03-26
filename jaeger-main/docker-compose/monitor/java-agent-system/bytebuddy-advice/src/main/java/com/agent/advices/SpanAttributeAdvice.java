package com.agent.advices;

import com.agent.helper.SpanAttributeHelper;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;
import java.lang.reflect.Method;
import java.util.Map;

public class SpanAttributeAdvice {
    private static final ThreadLocal<SpanAttributeHelper> helperHolder = new ThreadLocal<>();
    private static final ThreadLocal<Scope> scopeHolder = new ThreadLocal<>();

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onMethodEnter(
            @Advice.Origin Method method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] allArguments,
            @Advice.Local("paramNameMapping") Map<Integer, String> paramNameMapping
    ) {
        try {
            String className = target.getClass().getName();
            String methodName = method != null ? method.getName() : "unknown";
            Tracer tracer = getTracer();
            if (tracer == null) return;
            io.opentelemetry.api.trace.Span span = tracer.spanBuilder(className + "." + methodName)
                    .setParent(io.opentelemetry.context.Context.current())
                    .startSpan();
            Scope scope = span.makeCurrent();
            scopeHolder.set(scope);
            SpanAttributeHelper helper = new SpanAttributeHelper(span);
            helper.setBasicAttributes(className, methodName, Thread.currentThread().getId(), getLinuxThreadId(), getPid()); // getId() deprecated in Java 19+ but required for Java 8-17
            if (allArguments != null && paramNameMapping != null) {
                for (Map.Entry<Integer, String> entry : paramNameMapping.entrySet()) {
                    int paramIndex = entry.getKey();
                    String paramName = entry.getValue();
                    if (paramIndex < allArguments.length) {
                        helper.setParameterAttribute(paramName, allArguments[paramIndex]);
                    }
                }
            }
            helperHolder.set(helper);
        } catch (Exception e) {
            System.err.println("[SpanAttributeAdvice] onMethodEnter error: " + e.getMessage());
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMethodExit(@Advice.Thrown Throwable throwable) {
        try {
            SpanAttributeHelper helper = helperHolder.get();
            if (helper == null) return;
            if (throwable != null) helper.recordException(throwable);
            else helper.complete();
        } catch (Exception e) {
            System.err.println("[SpanAttributeAdvice] onMethodExit error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private static void cleanup() {
        Scope scope = scopeHolder.get();
        if (scope != null) scope.close();
        scopeHolder.remove();
        helperHolder.remove();
    }

    private static Tracer getTracer() {
        try {
            return io.opentelemetry.api.GlobalOpenTelemetry.getTracer("SpanAttributeAdvice", "1.0.0");
        } catch (Exception e) {
            return null;
        }
    }

    private static long getLinuxThreadId() {
        try {
            java.lang.reflect.Field tidField = Thread.class.getDeclaredField("tid");
            tidField.setAccessible(true);
            return tidField.getLong(Thread.currentThread());
        } catch (Exception e) {
            return Thread.currentThread().hashCode();
        }
    }

    private static long getPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Exception e) {
            return -1;
        }
    }
}
