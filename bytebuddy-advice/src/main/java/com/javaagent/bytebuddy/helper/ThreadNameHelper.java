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

    // 이니셜 (A-Z)
    private static final String[] INITIALS = {
        "SRV", "API", "WEB", "DBG", "TST", "DEV", "PRD", "STG"
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
     * 형식: {TraceId 7자}{SpanId 6자}{이니셜}{숫자}
     * 예: 3a5b7c12d456ABCTSRV12345
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

        // 포맷: {TraceId 7자}{SpanId 6자}{이니셜}{숫자}
        String newThreadName = formatThreadName(traceId, spanId);

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
     * 형식: {TraceId 7자}{SpanId 6자}{이니셜}{숫자}
     * 예: 3a5b7c12d456ABCTSRV1234
     */
    private static String formatThreadName(String traceId, String spanId) {
        // TraceId 7자 (앞 7자)
        String tracePrefix = traceId.substring(0, Math.min(7, traceId.length()));

        // SpanId 6자 (앞 6자)
        String spanPrefix = spanId.substring(0, Math.min(6, spanId.length()));

        // 이니셜 (무작위 선택)
        String initial = INITIALS[Math.abs(traceId.hashCode()) % INITIALS.length];

        // 숫자 (0-9999)
        int number = ThreadLocalRandom.current().nextInt(10000);

        return String.format("%s%s%s%04d", tracePrefix, spanPrefix, initial, number);
    }

    /**
     * 현재 스레드명이 변경 대상 패턴인지 확인
     */
    public static boolean isTargetThread() {
        return shouldRenameThread();
    }
}
