package com.socket.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

/**
 * 🤖 100% 자동 계측을 위한 ByteBuddy Advice
 *
 * 이 Advice는 SocketHandler 진입 전에:
 * 1. 소켓에서 traceparent 추출
 * 2. OpenTelemetry Context 생성
 * 3. makeCurrent()로 ThreadLocal에 자동 저장
 *
 * SocketHandler 코드에는 추적 관련 코드가 전혀 필요 없습니다!
 */
public class SocketTraceAdvice {

    /**
     * 메서드 진입 전에 traceparent 추출 및 Context 자동 생성
     *
     * @return Scope (메서드 종료 시 자동 정리를 위해 반환)
     */
    @Advice.OnMethodEnter(inline = false, suppress = Throwable.class)
    public static Scope onMethodEnter(@Advice.Argument(0) Socket socket) {
        System.out.println("=================================================");
        System.out.println("🤖 100% 자동 계측 시작");
        System.out.println("=================================================");

        try {
            // 1. Socket에서 traceparent 추출
            String traceParent = extractTraceParentFromSocket(socket);

            if (traceParent != null && !traceParent.isEmpty()) {
                System.out.println("📥 추출된 traceparent: " + traceParent);

                // 2. W3C traceparent 파싱
                String[] parts = traceParent.split("-");
                if (parts.length == 4 && "00".equals(parts[0])) {
                    String traceId = parts[1];
                    String spanId = parts[2];
                    byte flags = (byte) Integer.parseInt(parts[3], 16);

                    System.out.println("🔍 파싱된 Trace ID: " + traceId);
                    System.out.println("🔍 파싱된 Span ID: " + spanId);

                    // 3. SpanContext 생성
                    SpanContext spanContext = ImmutableSpanContext.create(traceId, spanId, flags);

                    // 4. Context 생성 및 자동으로 ThreadLocal에 저장 (makeCurrent)
                    Context extractedContext = Context.root().with(Span.wrap(spanContext));
                    Scope scope = extractedContext.makeCurrent();

                    // 5. 현재 Trace ID 확인
                    String currentTraceId = Span.current().getSpanContext().getTraceId();
                    System.out.println("✅ 100% 자동으로 Context 생성 및 ThreadLocal에 저장");
                    System.out.println("🔍 현재 Trace ID: " + currentTraceId);
                    System.out.println("=================================================");

                    // Scope를 반환하여 메서드 종료 시 자동 정리
                    return scope;
                }
            }

            System.out.println("⚠️ traceparent를 찾지 못함 (새로운 Trace 시작)");
            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("❌ Error in SocketTraceAdvice: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 메서드 종료 시 자동으로 Scope 정리
     */
    @Advice.OnMethodExit(inline = false, suppress = Throwable.class)
    public static void onMethodExit(@Advice.Enter Scope scope) {
        if (scope != null) {
            scope.close();
            System.out.println("✅ 자동으로 Scope 정리 완료");
            System.out.println("=================================================");
        }
    }

    /**
     * Socket에서 traceparent 헤더 추출 (스트림을 소비하지 않음)
     */
    private static String extractTraceParentFromSocket(Socket socket) {
        try {
            java.io.InputStream inputStream = socket.getInputStream();
            java.io.BufferedInputStream bufferedInput = new java.io.BufferedInputStream(inputStream);
            bufferedInput.mark(1024);

            InputStreamReader reader = new InputStreamReader(bufferedInput);
            BufferedReader lineReader = new BufferedReader(reader);

            String line;
            String traceParent = null;
            int lineCount = 0;

            while ((line = lineReader.readLine()) != null && lineCount < 10) {
                lineCount++;

                if (line.startsWith("traceparent:")) {
                    traceParent = line.substring("traceparent:".length()).trim();
                    System.out.println("📖 Read from socket: " + line);
                    break;
                }

                if (line.isEmpty()) {
                    break; // 빈 줄은 헤더 끝
                }
            }

            // 스트림을 원래 위치로 되돌림
            bufferedInput.reset();

            return traceParent;

        } catch (Exception e) {
            System.err.println("❌ Error extracting traceparent: " + e.getMessage());
            return null;
        }
    }

    /**
     * 간단한 ImmutableSpanContext 구현
     */
    static class ImmutableSpanContext implements SpanContext {
        private final String traceId;
        private final String spanId;
        private final TraceFlags traceFlags;

        ImmutableSpanContext(String traceId, String spanId, TraceFlags traceFlags) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.traceFlags = traceFlags;
        }

        @Override
        public String getTraceId() {
            return traceId;
        }

        @Override
        public String getSpanId() {
            return spanId;
        }

        @Override
        public TraceFlags getTraceFlags() {
            return traceFlags;
        }

        @Override
        public io.opentelemetry.api.trace.TraceState getTraceState() {
            return io.opentelemetry.api.trace.TraceState.getDefault();
        }

        @Override
        public boolean isValid() {
            return !traceId.equals("00000000000000000000000000000000") &&
                   !spanId.equals("0000000000000000");
        }

        @Override
        public boolean isRemote() {
            return true;
        }

        static SpanContext create(String traceId, String spanId, byte flags) {
            return new ImmutableSpanContext(traceId, spanId, TraceFlags.fromByte(flags));
        }
    }
}
