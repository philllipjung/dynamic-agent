package com.javaagent.bytebuddy.advices;

import com.javaagent.bytebuddy.helper.SpanAttributeHelper;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * SpanAttributeAdvice - Creates OpenTelemetry spans with parameter attributes
 *
 * Uses SpanAttributeHelper to set attributes.
 * Helper classes are injected into Bootstrap ClassLoader by HelperClassInjector.
 * Parameter mappings stored in Redis via ParameterMappingService.
 *
 * Uses Reflection to access ParameterMappingService from bytebuddy-agent module.
 */
public class SpanAttributeAdvice {
    private static final ThreadLocal<SpanAttributeHelper> helperHolder = new ThreadLocal<>();
    private static final ThreadLocal<Scope> scopeHolder = new ThreadLocal<>();

    /**
     * 파라미터 명 매핑 저장 (Redis에 저장)
     */
    public static void setParameterMapping(String className, String methodName, Map<Integer, String> mapping) {
        String key = className + "." + methodName;
        System.out.println("[SpanAttributeAdvice] Saving parameter mapping to Redis: " + key + " -> " + mapping);

        try {
            Class<?> redisService = Class.forName("com.javaagent.bytebuddy.redis.ParameterMappingService");
            java.lang.reflect.Method saveMethod = redisService.getMethod("saveMapping", String.class, String.class, Map.class);
            saveMethod.invoke(null, className, methodName, mapping);
            System.out.println("[SpanAttributeAdvice] Successfully saved to Redis");
        } catch (Exception e) {
            System.err.println("[SpanAttributeAdvice] Failed to save to Redis: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Redis에서 파라미터 매핑 획득
     */
    private static Map<Integer, String> getParameterMapping(String className, String methodName) {
        try {
            Class<?> redisService = Class.forName("com.javaagent.bytebuddy.redis.ParameterMappingService");
            java.lang.reflect.Method getMethod = redisService.getMethod("getMapping", String.class, String.class);
            Map<Integer, String> mapping = (Map<Integer, String>) getMethod.invoke(null, className, methodName);

            if (mapping != null && !mapping.isEmpty()) {
                System.out.println("[SpanAttributeAdvice] Loaded mapping from Redis: "
                    + className + "." + methodName + " -> " + mapping);
                return mapping;
            }
        } catch (Exception e) {
            System.out.println("[SpanAttributeAdvice] Redis lookup failed: " + e.getMessage());
        }

        return Collections.emptyMap();
    }

    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Method method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] allArguments
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

            // 기본 속성 설정
            helper.setBasicAttributes(className, methodName, Thread.currentThread().getId(), getLinuxThreadId(), getPid());

            // 파라미터 속성 추가
            Map<Integer, String> paramNameMapping = getParameterMapping(className, methodName);
            if (allArguments != null && paramNameMapping != null && !paramNameMapping.isEmpty()) {
                for (Map.Entry<Integer, String> entry : paramNameMapping.entrySet()) {
                    int paramIndex = entry.getKey();
                    String paramName = entry.getValue();
                    if (paramIndex < allArguments.length) {
                        helper.setParameterAttribute(paramName, allArguments[paramIndex]);
                    }
                }
            }

            helperHolder.set(helper);
        } catch (NoClassDefFoundError e) {
            System.err.println("[SpanAttributeAdvice] Helper class not found! Make sure HelperClassInjector is initialized.");
            System.err.println("[SpanAttributeAdvice] Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[SpanAttributeAdvice] onMethodEnter error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMethodExit(@Advice.Thrown Throwable throwable) {
        try {
            SpanAttributeHelper helper = helperHolder.get();
            if (helper == null) return;
            if (throwable != null) helper.recordException(throwable);
            else helper.complete();
        } catch (NoClassDefFoundError e) {
            System.err.println("[SpanAttributeAdvice] Helper class not found in onMethodExit!");
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
