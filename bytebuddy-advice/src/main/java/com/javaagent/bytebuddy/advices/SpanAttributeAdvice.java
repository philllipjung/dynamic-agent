package com.javaagent.bytebuddy.advices;

import com.javaagent.bytebuddy.helper.SpanAttributeHelper;
import net.bytebuddy.asm.Advice;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * SpanAttributeAdvice - Adds parameter attributes to existing OpenTelemetry spans
 *
 * Uses Span.current() to get the existing span from current context,
 * then adds parameter attributes without creating new spans.
 *
 * Uses SpanAttributeHelper to set attributes.
 * Helper classes are injected into Bootstrap ClassLoader by HelperClassInjector.
 * Parameter mappings stored in Redis via ParameterMappingService.
 *
 * Uses Reflection to access ParameterMappingService from bytebuddy-agent module.
 */
@SuppressWarnings("unchecked")
public class SpanAttributeAdvice {
    private static final ThreadLocal<SpanAttributeHelper> helperHolder = new ThreadLocal<>();

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

            // CRITICAL: Try to get span from current context (set by SpanHelper.makeCurrent())
            io.opentelemetry.api.trace.Span span = io.opentelemetry.api.trace.Span.current();

            // 유효한 스판이 없으면 로그 출력 후 종료
            if (span == null || !span.getSpanContext().isValid()) {
                System.err.println("[SpanAttributeAdvice] No current span found for " + className + "." + methodName);
                System.err.println("[SpanAttributeAdvice] Make sure SpanAdvice is applied first with makeCurrent()");
                return;
            }

            System.out.println("[SpanAttributeAdvice] Found current span: " + span);

            SpanAttributeHelper helper = new SpanAttributeHelper(span);

            // 파라미터 속성만 추가
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
        // 기존 스팬을 사용하므로 complete() 호출하지 않음
        // 속성만 추가했으므로 cleanup만 수행
        cleanup();
    }

    private static void cleanup() {
        helperHolder.remove();
    }
}
