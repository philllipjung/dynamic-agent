package com.javaagent.bytebuddy.advices;

import com.javaagent.bytebuddy.helper.JaegerLinkLookupService;
import com.javaagent.bytebuddy.helper.SpanHelper;
import com.javaagent.bytebuddy.helper.SpanAttributeHelper;
import com.javaagent.bytebuddy.helper.ThreadNameHelper;
import io.opentelemetry.api.trace.SpanContext;
import net.bytebuddy.asm.Advice;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpanAdvice {

    // Static map to store parameter mappings (key: "className.methodName", value: {index: paramName})
    public static final Map<String, Map<Integer, String>> parameterMappings = new ConcurrentHashMap<>();

    // Jaeger Link Lookup 설정
    private static final String SERVICE_NAME = "unknown_service:java";  // 또는 실제 service name
    private static final boolean ENABLE_JAEGER_LINK = true;             // Link 생성 활성화/비활성화

    /**
     * Set parameter mapping for a method (called by ByteBuddyAgent before applying advice)
     */
    public static void setParameterMapping(String className, String methodName, Map<Integer, String> mapping) {
        String key = className + "." + methodName;
        parameterMappings.put(key, mapping);
        System.out.println("[SpanAdvice] Parameter mapping registered for " + key + ": " + mapping);
    }

    @Advice.OnMethodEnter(inline = false)
    public static SpanHelper onMethodEnter(
            @Advice.Origin String method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] allArguments
    ) {
        // 🆕 스레드명 변경: 원래 이름 저장
        String originalThreadName = Thread.currentThread().getName();
        boolean renamedThread = false;

        System.err.println(">>> ADVICE ENTER: " + method);
        System.err.flush();

        String className = null;
        String methodName = null;
        String userId = null;

        // className과 methodName 추출
        if (target != null) {
            className = target.getClass().getName();
            methodName = extractMethodName(method);
            String key = className + "." + methodName;

            // userId 추출
            Map<Integer, String> paramMapping = parameterMappings.get(key);
            if (paramMapping != null && allArguments != null) {
                for (Map.Entry<Integer, String> entry : paramMapping.entrySet()) {
                    if ("userId".equals(entry.getValue())) {
                        Object value = allArguments[entry.getKey()];
                        if (value != null) {
                            userId = value.toString();
                        }
                    }
                }
            }
        }

        // ===== 🆕 Jaeger Link 생성 =====
        List<SpanContext> linkedContexts = Collections.emptyList();
        if (ENABLE_JAEGER_LINK && userId != null && className != null) {
            try {
                linkedContexts = findLinkedContexts(className, methodName, userId);
            } catch (Exception e) {
                System.err.println("[SpanAdvice] Error finding links: " + e.getMessage());
            }
        }
        // =============================

        // SpanHelper를 사용하여 span 생성 (Links 포함)
        SpanHelper spanHelper = SpanHelper.createSpanWithLinks(method, linkedContexts);
        System.err.println(">>> SPAN CREATED: " + (spanHelper != null && spanHelper.isValid()));
        System.err.flush();

        // ===== 🆕 스레드명 변경 =====
        System.err.println(">>> [ThreadName] Check: spanHelper=" + (spanHelper != null && spanHelper.isValid()) + ", isTarget=" + ThreadNameHelper.isTargetThread());
        System.err.println(">>> [ThreadName] Current thread: " + Thread.currentThread().getName());
        if (spanHelper != null && spanHelper.isValid() && ThreadNameHelper.isTargetThread()) {
            // 실제 추적 컨텍스트의 Span 객체 전달
            System.err.println(">>> [ThreadName] Renaming thread...");
            ThreadNameHelper.renameThread(spanHelper.getSpan());
            renamedThread = true;
            // 원래 스레드명을 spanHelper에 저장하여 나중에 복원
            spanHelper.setOriginalThreadName(originalThreadName);
        }
        // =============================

        // 파라미터 속성 추가 (파라미터 매핑이 있는 경우)
        if (spanHelper != null && spanHelper.isValid() && target != null) {
            try {
                String key = className + "." + methodName;
                System.err.println(">>> DEBUG: Looking up key: " + key);
                System.err.println(">>> DEBUG: userId=" + userId);
                System.err.println(">>> DEBUG: links=" + linkedContexts.size());

                Map<Integer, String> paramMapping = parameterMappings.get(key);
                System.err.println(">>> DEBUG: paramMapping=" + paramMapping);

                if (paramMapping != null && !paramMapping.isEmpty() && allArguments != null) {
                    SpanAttributeHelper attrHelper = new SpanAttributeHelper(spanHelper.getSpan());
                    for (Map.Entry<Integer, String> entry : paramMapping.entrySet()) {
                        int paramIndex = entry.getKey();
                        String paramName = entry.getValue();
                        if (paramIndex < allArguments.length) {
                            attrHelper.setParameterAttribute(paramName, allArguments[paramIndex]);
                            System.err.println(">>> Set attribute: " + paramName + " = " + allArguments[paramIndex]);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println(">>> Failed to set attributes: " + e.getMessage());
                e.printStackTrace();
            }
        }
        System.err.flush();

        return spanHelper;
    }

    /**
     * 🆕 Jaeger에서 link를 위한 target span 찾기
     *
     * test1 → test2, test2 → test1 매핑
     *
     * NOTE: public으로 변경해야 inline 코드에서 접근 가능
     */
    public static List<SpanContext> findLinkedContexts(
        String className,
        String methodName,
        String userId
    ) {
        String targetService = null;
        String targetOperation = null;

        // test1 → test2
        if (className.contains("Test1Controller")) {
            targetService = SERVICE_NAME;
            targetOperation = "public java.lang.String com.test.service.test2.controller.Test2Controller.test2(java.lang.String)";
        }
        // test2 → test1
        else if (className.contains("Test2Controller")) {
            targetService = SERVICE_NAME;
            targetOperation = "public java.lang.String com.test.service.test1.controller.Test1Controller.test1(java.lang.String)";
        }

        if (targetService == null || targetOperation == null) {
            return Collections.emptyList();
        }

        System.out.println("[SpanAdvice] Looking for links: service=" + targetService
            + ", operation=" + targetOperation + ", userId=" + userId);

        return JaegerLinkLookupService.findTargetSpanContexts(
            targetService,
            targetOperation,
            userId
        );
    }

    @Advice.OnMethodExit(inline = false)
    public static void onMethodExit(@Advice.Enter SpanHelper spanHelper) {
        System.err.println("<<< ADVICE EXIT");

        if (spanHelper != null && spanHelper.isValid()) {
            spanHelper.complete();
            System.err.println("<<< SPAN COMPLETED");

            // ===== 🆕 스레드명 복원 =====
            String originalThreadName = spanHelper.getOriginalThreadName();
            if (originalThreadName != null) {
                try {
                    String currentName = Thread.currentThread().getName();
                    Thread.currentThread().setName(originalThreadName);
                    System.out.println("===============================================");
                    System.out.println("[ThreadName] RESTORED THREAD");
                    System.out.println("[ThreadName] From: " + currentName);
                    System.out.println("[ThreadName] To:   " + originalThreadName);
                    System.out.println("===============================================");
                } catch (Exception e) {
                    System.err.println("<<< Failed to restore thread name: " + e.getMessage());
                }
            }
            // =============================
        } else {
            System.err.println("<<< SPAN WAS NULL!");
        }
        System.err.flush();
    }

    public static String extractMethodName(String fullMethod) {
        // "public java.lang.String com.example.MyClass.myMethod(java.lang.String)" -> "myMethod"
        // 먼저 '(' 위치를 찾습니다
        int parenIndex = fullMethod.indexOf('(');
        if (parenIndex < 0) {
            return fullMethod;
        }

        // '(' 앞부분만 추출
        String beforeParen = fullMethod.substring(0, parenIndex);

        // 마지막 공백과 마지막 dot을 찾습니다
        int lastSpace = beforeParen.lastIndexOf(' ');
        int lastDot = beforeParen.lastIndexOf('.');

        // dot이 space보다 뒤에 있으면 (즉, 패키지/클래스 부분에 있는 dot이면)
        // 그 다음 메서드 이름이 옵니다
        if (lastDot > lastSpace && lastDot > 0) {
            return beforeParen.substring(lastDot + 1);
        } else if (lastSpace > 0) {
            // space가 마지막이면 space 다음이 메서드 이름
            return beforeParen.substring(lastSpace + 1);
        }

        return beforeParen;
    }
}
