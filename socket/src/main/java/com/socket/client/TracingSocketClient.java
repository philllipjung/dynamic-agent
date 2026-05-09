package com.socket.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;

/**
 * Trace Context를 전송하는 소켓 클라이언트
 *
 * 이 클라이언트는 W3C traceparent 형식으로 Trace Context를 생성하여
 * 소켓 메시지 헤더에 포함하여 서버로 전송합니다.
 */
public class TracingSocketClient {

    private final String host;
    private final int port;

    public TracingSocketClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Trace Context를 생성하여 서버로 전송합니다.
     */
    public void sendWithTrace(String message) {
        // 테스트용 traceparent 생성
        String traceId = generateTraceId();
        String spanId = generateSpanId();
        String traceParent = String.format("00-%s-%s-01", traceId, spanId);

        System.out.println("=================================================");
        System.out.println("Sending message with trace context");
        System.out.println("Trace ID: " + traceId);
        System.out.println("Span ID: " + spanId);
        System.out.println("Traceparent: " + traceParent);
        System.out.println("=================================================");

        // 소켓 연결 및 메시지 전송
        try (Socket socket = new Socket(host, port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))
        ) {
            // traceparent 헤더 전송
            writer.println("traceparent: " + traceParent);
            writer.println(); // 빈 줄로 헤더 끝 표시
            writer.println(message);
            writer.flush();

            System.out.println("Message sent: " + message);

            // 서버 응답 수신
            String line;
            System.out.println("\n=== Server Response ===");
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                System.out.println(line);
            }

            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Trace Context 없이 메시지를 전송합니다 (비교용).
     */
    public void sendWithoutTrace(String message) {
        System.out.println("=================================================");
        System.out.println("Sending message WITHOUT trace context");
        System.out.println("=================================================");

        try (Socket socket = new Socket(host, port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))
        ) {
            // traceparent 없이 메시지만 전송
            writer.println(message);
            writer.flush();

            System.out.println("Message sent: " + message);

            // 서버 응답 수신
            String line;
            System.out.println("\n=== Server Response ===");
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                System.out.println(line);
            }

            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 16바이트 trace ID 생성 (W3C 표준)
     */
    private String generateTraceId() {
        // 32자 hex 문자열 (UUID는 하이픈 제거하면 32자)
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 8바이트 span ID 생성 (W3C 표준)
     */
    private String generateSpanId() {
        // 16자 hex 문자열 (UUID 앞부분 16자만 사용)
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9999;

        TracingSocketClient client = new TracingSocketClient(host, port);

        // Trace Context로 메시지 전송
        client.sendWithTrace("Hello, Socket Server with Trace Context!");

        // 잠시 대기
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Trace Context 없이 메시지 전송 (비교용)
        client.sendWithoutTrace("Hello, Socket Server (NO TRACE)!");
    }
}
