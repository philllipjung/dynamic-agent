package com.socket.server;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

/**
 * 소켓 서버 - Spring Boot Component
 *
 * 이 서버는 클라이언트 연결을 수락하고 SocketHandler에 처리를 위임합니다.
 * SocketHandler는 ByteBuddy Agent에 의해 자동 계측됩니다.
 */
@Component
public class SocketServer {

    private final int port;
    private final ExecutorService executorService;
    private final SocketHandler socketHandler;
    private ServerSocket serverSocket;
    private volatile boolean running;

    @Autowired
    public SocketServer(ExecutorService executorService, SocketHandler socketHandler) {
        this.port = 9998;
        this.executorService = executorService;
        this.socketHandler = socketHandler;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;

        System.out.println("=================================================");
        System.out.println("🚀 Spring Boot Socket Server Started on port: " + port);
        System.out.println("🔍 ByteBuddy Agent가 SocketHandler를 자동 계측합니다");
        System.out.println("⏳ 기다리는 중...");
        System.out.println("=================================================");

        // 별도의 스레드에서 서버 실행
        new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("\n=================================================");
                    System.out.println("✅ Client connected: " + clientSocket.getRemoteSocketAddress());
                    System.out.println("=================================================");

                    // 클라이언트 요청을 비동기로 처리
                    executorService.submit(() -> {
                        // Spring Bean으로 주입받은 SocketHandler 사용
                        socketHandler.handle(clientSocket);
                    });

                } catch (IOException e) {
                    if (running) {
                        System.err.println("❌ Error accepting client connection: " + e.getMessage());
                    }
                }
            }
        }, "socket-server-acceptor").start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("❌ Error closing server socket: " + e.getMessage());
            }
        }
        System.out.println("🛑 Socket Server stopped");
    }
}
