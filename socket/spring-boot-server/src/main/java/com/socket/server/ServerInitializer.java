package com.socket.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 서버 초기화 - Spring Boot 시작 시 소켓 서버 실행
 */
@Component
public class ServerInitializer implements ApplicationRunner {

    @Autowired
    private SocketServer socketServer;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        socketServer.start();
    }
}
