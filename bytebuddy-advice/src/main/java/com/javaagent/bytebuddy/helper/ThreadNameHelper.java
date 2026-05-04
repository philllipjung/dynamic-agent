package com.javaagent.bytebuddy.helper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 스레드명 관리 Helper
 *
 * OpenTelemetry TraceId와 SpanId를 사용하여 고유한 스레드명 생성
 */
public class ThreadNameHelper {

    // 스레드명을 변경할 대상 패턴
    private static final Pattern[] TARGET_PATTERNS = {
        Pattern.compile(".*")  // 🔴 TEMP: 모든 스레드에 적용 (테스트용)
    };

    // 이니셜 (1자리)
    private static final String[] INITIALS = {
        "S", "A", "W", "D", "T", "P", "R", "H", "X", "Z"
    };

    /**
     * 현재 스레드의 이름이 변경 대상인지 확인
     */
    public static boolean shouldRenameThread() {
        String currentName = Thread.currentThread().getName();
        for (Pattern pattern : TARGET_PATTERNS) {
            if (pattern.matcher(currentName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 현재 스레드의 이름을 변경 (실제 추적 컨텍스트에서 TraceId/SpanId 읽기)
     *
     * 형식: {TraceId 6자}{SpanId 6자}{이니셜 1자}{숫자 2자}
     * 예: 3a5b7c1d456R10 (reactor-http-nio-10인 경우)
     */
    public static void renameThread() {
        renameThread(Span.current());
    }

    /**
     * 현재 스레드의 이름을 변경 (Span에서 SpanContext 직접 읽기)
     *
     * @param span Span 객체 (null 가능)
     */
    public static void renameThread(Span span) {
        String currentName = Thread.currentThread().getName();

        // Span에서 SpanContext 가져오기
        if (span == null || !span.getSpanContext().isValid()) {
            return;
        }

        SpanContext context = span.getSpanContext();
        String traceId = context.getTraceId();
        String spanId = context.getSpanId();

        if (traceId == null || spanId == null) {
            return;
        }

        // 포맷: {TraceId 6자}{SpanId 6자}{이니셜 1자}{숫자 2자}
        String newThreadName = formatThreadName(traceId, spanId, currentName);

        // 스레드명 변경
        try {
            Thread.currentThread().setName(newThreadName);
            // 로그에 TraceId와 SpanId 출력
            System.out.println("===============================================");
            System.out.println("[ThreadName] RENAMED THREAD");
            System.out.println("[ThreadName] Original Name: " + currentName);
            System.out.println("[ThreadName] New Name:      " + newThreadName);
            System.out.println("[ThreadName] TraceId:       " + traceId);
            System.out.println("[ThreadName] SpanId:        " + spanId);
            System.out.println("===============================================");
        } catch (Exception e) {
            System.err.println("[ThreadName] Failed to rename thread: " + e.getMessage());
        }
    }

    /**
     * TraceId와 SpanId로 형식화된 스레드명 생성
     * 형식: {TraceId 6자}{SpanId 6자}{이니셜 1자}{숫자 2자}
     * 예: 3a5b7c1d456H02 (http-nio-8081-exec-2인 경우)
     *     3a5b7c1d456R10 (reactor-http-nio-10인 경우)
     */
    private static String formatThreadName(String traceId, String spanId, String currentName) {
        // TraceId 6자 (앞 6자)
        String tracePrefix = traceId.substring(0, Math.min(6, traceId.length()));

        // SpanId 6자 (앞 6자)
        String spanPrefix = spanId.substring(0, Math.min(6, spanId.length()));

        // 이니셜 1자 (원래 스레드명의 첫 번째 알파벳)
        String initial = extractInitial(currentName);

        // 숫자 2자 (원래 스레드명에서 추출, 없으면 무작위)
        int threadNumber = extractThreadNumber(currentName);

        return String.format("%s%s%s%02d", tracePrefix, spanPrefix, initial, threadNumber);
    }

    /**
     * 스레드명에서 첫 번째 알파벳(이니셜) 추출
     * 예: http-nio-8081-exec-2 → H
     *     reactor-http-nio-10 → R
     *     pool-1-thread-3 → P
     *
     * @param threadName 스레드명
     * @return 추출된 이니셜 (없으면 무작위 1자)
     */
    private static String extractInitial(String threadName) {
        if (threadName == null || threadName.isEmpty()) {
            return INITIALS[Math.abs(ThreadLocalRandom.current().nextInt()) % INITIALS.length];
        }

        // 첫 번째 단어의 첫 번째 알파벳 찾기
        // 예: "http-nio-8081-exec-2" → 'h' → 'H'
        for (int i = 0; i < threadName.length(); i++) {
            char c = threadName.charAt(i);
            if (Character.isLetter(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }

        // 알파벳을 찾지 못하면 무작위 이니셜 반환
        return INITIALS[Math.abs(ThreadLocalRandom.current().nextInt()) % INITIALS.length];
    }

    /**
     * 스레드명에서 숫자 추출
     * 예: reactor-http-nio-10 → 10
     *     http-nio-8081-exec-2 → 2
     *     pool-1-thread-3 → 3
     *
     * @param threadName 스레드명
     * @return 추출된 숫자 (없으면 0-99 사이 무작위 값)
     */
    private static int extractThreadNumber(String threadName) {
        if (threadName == null) {
            return ThreadLocalRandom.current().nextInt(100);
        }

        // 스레드명의 마지막 숫자 추출
        java.util.regex.Pattern numberPattern = java.util.regex.Pattern.compile("(\\d+)$");
        java.util.regex.Matcher matcher = numberPattern.matcher(threadName);

        if (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group(1));
                // 2자리로 제한 (0-99)
                return number % 100;
            } catch (NumberFormatException e) {
                // 숫자 변환 실패 시 무작위 값 반환
            }
        }

        // 숫자를 찾지 못하면 무작위 값 반환
        return ThreadLocalRandom.current().nextInt(100);
    }

    /**
     * 현재 스레드명이 변경 대상 패턴인지 확인
     */
    public static boolean isTargetThread() {
        return shouldRenameThread();
    }
}
