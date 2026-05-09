package com.socket.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

/**
 * 🔥 100% 자동 계측 소켓 핸들러
 *
 * 이 클래스는 비즈니스 로직만 포함합니다.
 * 추적 관련 코드는 전혀 없습니다 - ByteBuddy Agent가 자동으로 처리!
 *
 * - traceparent 추출: 자동 (SocketTraceAdvice)
 * - Context 생성: 자동 (SocketTraceAdvice)
 * - ThreadLocal 저장: 자동 (SocketTraceAdvice)
 * - HTTP 전파: 자동 (OpenTelemetry Java Agent)
 */
@Component
public class SocketHandler {

    @Autowired
    private RestTemplate restTemplate;

    /**
     * 소켓 요청 처리 - 순수 비즈니스 로직만!
     */
    public void handle(Socket socket) {
        System.out.println("=================================================");
        System.out.println("📦 SocketHandler.handle() 호출됨");
        System.out.println("🔥 추적 코드 없이 순수 비즈니스 로직만!");
        System.out.println("=================================================");

        try {
            // 1. 클라이언트 메시지 처리
            processClientMessage(socket);

            // 2. HTTP 서버 호출
            callHttpServer();

            System.out.println("=================================================");
            System.out.println("✅ SocketHandler.handle() 완료");
            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /**
     * 클라이언트 메시지 처리 - 비즈니스 로직
     */
    private void processClientMessage(Socket socket) throws Exception {
        socket.setSoTimeout(1000); // 1초 타임아웃
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
        );

        String line;
        StringBuilder message = new StringBuilder();
        try {
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) break;
                message.append(line);
            }
        } catch (java.net.SocketTimeoutException e) {
            // 타임아웃은 무시
        }

        if (message.length() > 0) {
            System.out.println("📨 받은 메시지: " + message.toString());
        }
    }

    /**
     * HTTP 서버 호출 - 비즈니스 로직
     */
    private String callHttpServer() {
        System.out.println("=================================================");
        System.out.println("🌐 HTTP 서버 호출 시작");
        System.out.println("=================================================");

        try {
            // RestTemplate으로 HTTP 서버 호출
            String response = restTemplate.getForObject(
                    "http://localhost:8080/hello",
                    String.class
            );

            System.out.println("📥 HTTP 응답: " + response);
            System.out.println("=================================================");

            return response;

        } catch (Exception e) {
            System.err.println("❌ HTTP 호출 실패: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
