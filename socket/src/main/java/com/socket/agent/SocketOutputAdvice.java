package com.socket.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import net.bytebuddy.asm.Advice;

import java.io.PrintWriter;
import java.lang.reflect.Field;

/**
 * ByteBuddy Advice for Socket Output Auto-Instrumentation
 *
 * 이 Advice는 PrintWriter.println() 호출을 가로채서
 * 현재 OpenTelemetry Span에서 traceparent를 추출하여
 * 자동으로 헤더로 추가합니다.
 *
 * 🔥 100% 자동 계측: 클라이언트 코드에 traceparent 코드가 필요 없습니다!
 */
public class SocketOutputAdvice {

    // 각 PrintWriter 인스턴스에서 헤더 추가 여부 추적
    private static final java.util.concurrent.ConcurrentHashMap<PrintWriter, Boolean> headerAddedMap = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * PrintWriter.println() 진입 전에 traceparent 헤더를 자동으로 추가합니다.
     */
    @Advice.OnMethodEnter(inline = false, suppress = Throwable.class)
    public static void onMethodEnter(
            @Advice.This PrintWriter writer,
            @Advice.Argument(0) String message) {

        // 이 PrintWriter에 이미 헤더를 추가했는지 확인
        if (headerAddedMap.containsKey(writer)) {
            return; // 이미 헤더를 추가했으면 skip
        }

        try {
            // 현재 Context에서 Span 추출
            Context currentContext = Context.current();
            Span span = Span.fromContext(currentContext);

            // 유효한 Span이 있는지 확인
            if (span != null && span.getSpanContext().isValid()) {
                String traceId = span.getSpanContext().getTraceId();
                String spanId = span.getSpanContext().getSpanId();
                byte traceFlags = span.getSpanContext().getTraceFlags().asByte();

                // W3C traceparent 형식 생성
                String traceParent = String.format("00-%s-%s-%02x",
                        traceId,
                        spanId,
                        traceFlags & 0xFF);

                System.out.println("=================================================");
                System.out.println("🤖 SocketOutputAdvice: 자동으로 traceparent 헤더 추가");
                System.out.println("🔍 Trace ID: " + traceId);
                System.out.println("🔍 Span ID: " + spanId);
                System.out.println("📝 Traceparent: " + traceParent);
                System.out.println("=================================================");

                // 원래 메시지 전에 traceparent 헤더 추가
                writer.println("traceparent: " + traceParent);
                writer.println(); // 빈 줄로 헤더 끝 표시

                // 헤더 추가 완료 표시
                headerAddedMap.put(writer, true);

                System.out.println("✅ traceparent 헤더가 자동으로 추가되었습니다!");
                System.out.println("=================================================");
            } else {
                System.out.println("⚠️ 유효한 Span이 없습니다 (새로운 Trace 시작)");
                headerAddedMap.put(writer, true); // 헤더 없이 진행
            }

        } catch (Exception e) {
            System.err.println("❌ Error in SocketOutputAdvice: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
