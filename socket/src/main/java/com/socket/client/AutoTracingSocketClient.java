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
 * 이 클라이언트는 OpenTelemetry Span을 생성하고,
 * ByteBuddy Agent가 자동으로 traceparent를 추출하여 헤더에 추가합니다.
 */
public class AutoTracingSocketClient {

    private final String host;
    private final int port;
    private final Tracer tracer;

    public AutoTracingSocketClient(String host, int port, Tracer tracer) {
        this.host = host;
        this.port = port;
        this.tracer = tracer;
    }

    /**
     * 메시지 전송 - traceparent는 Agent가 자동으로 추가합니다!
     */
    public void send(String message) {
        // Span 생성
        Span span = tracer.spanBuilder("AutoTracingSocketClient.send")
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            String traceId = span.getSpanContext().getTraceId();
            String spanId = span.getSpanContext().getSpanId();

            System.out.println("=================================================");
            System.out.println("🚀 Auto Tracing Socket Client");
            System.out.println("=================================================");
            System.out.println("✨ Span 생성됨");
            System.out.println("🔍 Trace ID: " + traceId);
            System.out.println("🔍 Span ID: " + spanId);
            System.out.println("🤖 Agent가 traceparent를 자동으로 추가합니다");
            System.out.println("=================================================");

            try (Socket socket = new Socket(host, port);
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream()))
            ) {
                // 비즈니스 메시지만 전송 (traceparent는 Agent가 자동 추가)
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
        // OpenTelemetry Tracer 초기화 (noop으로 테스트)
        io.opentelemetry.api.OpenTelemetry openTelemetry = io.opentelemetry.api.OpenTelemetry.noop();
        Tracer tracer = openTelemetry.getTracer("AutoTracingSocketClient", "1.0.0");

        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9998;

        AutoTracingSocketClient client = new AutoTracingSocketClient(host, port, tracer);

        client.send("Hello from Auto-Tracing Client! 🚀");
    }
}
