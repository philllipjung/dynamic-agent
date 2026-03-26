package com.javaagent.bytebuddy.advices;

import com.javaagent.bytebuddy.helper.SpanLinkHelper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Span Link Advice - Creates spans with links to other traces
 *
 * Uses SpanLinkHelper to add links based on attribute matching
 *
 * Uses Reflection to access Redis/OpenSearch services from bytebuddy-agent module.
 */
@SuppressWarnings("unchecked")
public class SpanLinkAdvice {

    private static final ThreadLocal<SpanLinkHelper> helperHolder = new ThreadLocal<>();

    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Method method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] allArguments
    ) {
        try {
            String className = target.getClass().getName();
            String methodName = method != null ? method.getName() : "unknown";
            String spanName = className + "." + methodName;

            Tracer tracer = getTracer();
            if (tracer == null) return;

            Span span = tracer.spanBuilder(spanName)
                    .setParent(io.opentelemetry.context.Context.current())
                    .startSpan();

            SpanLinkHelper helper = new SpanLinkHelper(span);
            helper.makeCurrent();
            helperHolder.set(helper);

            helper.setBasicAttributes(className, methodName,
                    Thread.currentThread().getId(), getLinuxThreadId(), getPid());

            // 파라미터 속성 설정
            Map<Integer, String> paramMapping = getParameterMapping(className, methodName);
            if (allArguments != null && paramMapping != null) {
                for (Map.Entry<Integer, String> entry : paramMapping.entrySet()) {
                    int paramIndex = entry.getKey();
                    String paramName = entry.getValue();
                    if (paramIndex < allArguments.length) {
                        helper.setParameterAttribute(paramName, allArguments[paramIndex]);
                    }
                }
            }

            // ★ 스팬 링크 처리
            processSpanLinks(helper, className, methodName);

        } catch (Exception e) {
            System.err.println("[SpanLinkAdvice] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 스팬 링크 처리
     *
     * @param helper Span Link Helper
     * @param className 클래스명
     * @param methodName 메서드명
     */
    private static void processSpanLinks(SpanLinkHelper helper, String className, String methodName) {
        try {
            String sourceSpanName = className + "." + methodName;

            // Redis에서 링크 설정 조회
            List<?> linkConfigs = getLinkConfigsFromRedis(sourceSpanName);

            if (linkConfigs.isEmpty()) {
                return;
            }

            System.out.println("[SpanLinkAdvice] Processing " + linkConfigs.size() + " link configs");

            for (Object configObj : linkConfigs) {
                // Reflection으로 config 객체 접근
                String linkId = getConfigLinkId(configObj);
                boolean enabled = getConfigEnabled(configObj);

                if (!enabled) continue;

                String sourceAttributeKey = getConfigSourceAttributeKey(configObj);
                String targetSpanName = getConfigTargetSpanName(configObj);
                String targetAttributeKey = getConfigTargetAttributeKey(configObj);
                String linkType = getConfigLinkType(configObj);

                // 소스 속성 값 추출
                String sourceValue = helper.getAttribute(sourceAttributeKey);
                if (sourceValue == null || sourceValue.isEmpty()) {
                    System.out.println("[SpanLinkAdvice] Source attribute not found: " + sourceAttributeKey);
                    continue;
                }

                System.out.println("[SpanLinkAdvice] Processing link: " + linkId);
                System.out.println("[SpanLinkAdvice]   Source value: " + sourceValue);

                // OpenSearch에서 타겟 Trace 검색
                List<?> targetTraces = findTargetTraces(targetSpanName, targetAttributeKey, sourceValue);

                if (targetTraces.isEmpty()) {
                    System.out.println("[SpanLinkAdvice]   No matching target traces found");
                    continue;
                }

                // 링크 추가
                for (Object traceObj : targetTraces) {
                    String traceID = getTraceID(traceObj);
                    String spanID = getSpanID(traceObj);

                    helper.addSpanLink(traceID, spanID, linkType);
                    System.out.println("[SpanLinkAdvice]   Added link to trace: " + traceID);
                }
            }

        } catch (Exception e) {
            System.err.println("[SpanLinkAdvice] Error processing span links: " + e.getMessage());
        }
    }

    // ========== Reflection Helper Methods for SpanLinkConfig ==========

    private static String getConfigLinkId(Object config) {
        try {
            Class<?> configClass = Class.forName("com.javaagent.commons.SpanLinkConfig");
            java.lang.reflect.Method method = configClass.getMethod("getLinkId");
            return (String) method.invoke(config);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean getConfigEnabled(Object config) {
        try {
            Class<?> configClass = Class.forName("com.javaagent.commons.SpanLinkConfig");
            java.lang.reflect.Method method = configClass.getMethod("isEnabled");
            return (Boolean) method.invoke(config);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getConfigSourceAttributeKey(Object config) {
        try {
            Class<?> configClass = Class.forName("com.javaagent.commons.SpanLinkConfig");
            java.lang.reflect.Method method = configClass.getMethod("getSourceAttributeKey");
            return (String) method.invoke(config);
        } catch (Exception e) {
            return "";
        }
    }

    private static String getConfigTargetSpanName(Object config) {
        try {
            Class<?> configClass = Class.forName("com.javaagent.commons.SpanLinkConfig");
            java.lang.reflect.Method method = configClass.getMethod("getTargetSpanName");
            return (String) method.invoke(config);
        } catch (Exception e) {
            return "";
        }
    }

    private static String getConfigTargetAttributeKey(Object config) {
        try {
            Class<?> configClass = Class.forName("com.javaagent.commons.SpanLinkConfig");
            java.lang.reflect.Method method = configClass.getMethod("getTargetAttributeKey");
            return (String) method.invoke(config);
        } catch (Exception e) {
            return "";
        }
    }

    private static String getConfigLinkType(Object config) {
        try {
            Class<?> configClass = Class.forName("com.javaagent.commons.SpanLinkConfig");
            java.lang.reflect.Method method = configClass.getMethod("getLinkType");
            Object enumResult = method.invoke(config);
            return enumResult != null ? enumResult.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ========== Reflection Helper Methods for TraceLink ==========

    private static String getTraceID(Object traceLink) {
        try {
            Class<?> traceLinkClass = Class.forName("com.javaagent.commons.TraceLink");
            java.lang.reflect.Method method = traceLinkClass.getMethod("getTraceID");
            return (String) method.invoke(traceLink);
        } catch (Exception e) {
            return "";
        }
    }

    private static String getSpanID(Object traceLink) {
        try {
            Class<?> traceLinkClass = Class.forName("com.javaagent.commons.TraceLink");
            java.lang.reflect.Method method = traceLinkClass.getMethod("getSpanID");
            return (String) method.invoke(traceLink);
        } catch (Exception e) {
            return "";
        }
    }

    // ========== Service Access Methods ==========

    /**
     * Redis에서 링크 설정 조회
     */
    private static List<?> getLinkConfigsFromRedis(String sourceSpanName) {
        try {
            Class<?> redisService = Class.forName("com.javaagent.bytebuddy.redis.SpanLinkService");
            java.lang.reflect.Method getMethod = redisService.getMethod("getLinksForSource", String.class);
            return (List<?>) getMethod.invoke(null, sourceSpanName);
        } catch (Exception e) {
            System.out.println("[SpanLinkAdvice] No link configs: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * OpenSearch에서 타겟 Trace 검색
     */
    private static List<?> findTargetTraces(String targetSpanName, String targetAttributeKey, String attributeValue) {
        try {
            Class<?> linkManager = Class.forName("com.javaagent.bytebuddy.opensearch.SpanLinkManager");
            java.lang.reflect.Method findMethod = linkManager.getMethod(
                    "findMatchingTraces",
                    String.class,
                    String.class,
                    String.class
            );
            return (List<?>) findMethod.invoke(null, targetSpanName, targetAttributeKey, attributeValue);
        } catch (Exception e) {
            System.err.println("[SpanLinkAdvice] Error finding target traces: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Redis에서 파라미터 매핑 조회
     */
    private static Map<Integer, String> getParameterMapping(String className, String methodName) {
        try {
            Class<?> redisService = Class.forName("com.javaagent.bytebuddy.redis.ParameterMappingService");
            java.lang.reflect.Method getMethod = redisService.getMethod("getMapping", String.class, String.class);
            Map<Integer, String> mapping = (Map<Integer, String>) getMethod.invoke(null, className, methodName);
            if (mapping != null) {
                return mapping;
            }
        } catch (Exception e) {
            System.out.println("[SpanLinkAdvice] Parameter mapping lookup failed: " + e.getMessage());
        }
        return Collections.emptyMap();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMethodExit(@Advice.Thrown Throwable throwable) {
        try {
            SpanLinkHelper helper = helperHolder.get();
            if (helper == null) return;
            if (throwable != null) helper.recordException(throwable);
            else helper.complete();
        } finally {
            cleanup();
        }
    }

    private static void cleanup() {
        SpanLinkHelper helper = helperHolder.get();
        if (helper != null) {
            helper.closeScope();
        }
        helperHolder.remove();
    }

    private static Tracer getTracer() {
        try {
            return io.opentelemetry.api.GlobalOpenTelemetry.getTracer("SpanLinkAdvice", "1.0.0");
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
