package com.socket.client;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * 🔥 100% 자동 계측 소켓 클라이언트
 *
 * 이 클라이언트는:
 * - traceparent 생성 코드: 0줄 ✅
 * - traceparent 전송 코드: 0줄 ✅
 * - Span 생성 필요: 1줄 (Context 시작용)
 *
 * ByteBuddy Agent가 traceparent를 자동으로 생성하고 전송합니다!
 */
public class SimpleSocketClient {

    private final String host;
    private final int port;
    private final Tracer tracer;

    public SimpleSocketClient(String host, int port, Tracer tracer) {
        this.host = host;
        this.port = port;
        this.tracer = tracer;
    }

    /**
     * 메시지 전송 - traceparent 전송 코드가 없습니다!
     *
     * ByteBuddy Agent가 자동으로:
     * 1. 현재 Trace ID 확인
     * 2. traceparent 생성
     * 3. 소켓에 헤더 추가
     */
    public void send(String message) {
        // Span 생성 (이것만 필요!)
        Span span = tracer.spanBuilder("SimpleSocketClient.send").startSpan();

        try (Scope scope = span.makeCurrent()) {
            String traceId = span.getSpanContext().getTraceId();
            String spanId = span.getSpanContext().getSpanId();

            System.out.println("=================================================");
            System.out.println("📦 SimpleSocketClient.send() 호출됨");
            System.out.println("🔥 traceparent 전송 코드 없이 비즈니스 로직만!");
            System.out.println("🔍 Trace ID: " + traceId);
            System.out.println("🔍 Span ID: " + spanId);
            System.out.println("🤖 Agent가 traceparent를 자동으로 추가합니다!");
            System.out.println("=================================================");

            try (Socket socket = new Socket(host, port);
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream()))
            ) {
                // 비즈니스 메시지만 전송 (traceparent는 Agent가 자동 추가!)
                writer.println(message);
                writer.flush();

                System.out.println("📨 Message sent: " + message);

                // 서버 응답 수신
                String line;
                System.out.println("\n=== 📩 Server Response ===");
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    System.out.println(line);
                }

                System.out.println("=================================================");
                span.addEvent("Message sent");

            } catch (Exception e) {
                System.err.println("❌ Error: " + e.getMessage());
                span.recordException(e);
            }

        } finally {
            span.end();
        }
    }

    public static void main(String[] args) {
        // OpenTelemetry 초기화 (Agent가 있다면 Agent의 Tracer 사용)
        io.opentelemetry.api.OpenTelemetry openTelemetry =
            io.opentelemetry.api.GlobalOpenTelemetry.get();

        Tracer tracer = openTelemetry.getTracer("SimpleSocketClient", "1.0.0");

        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9998;

        SimpleSocketClient client = new SimpleSocketClient(host, port, tracer);

        // 완전한 자동 계측 테스트
        System.out.println("🚀 100% 자동 계측 테스트 시작");
        System.out.println("✨ ByteBuddy Agent가 traceparent를 자동으로 추가합니다!");
        System.out.println();

        client.send("Hello from Simple Client! 🎉");

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        client.send("Second message! 🚀");
    }
}
