package com.socket.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring Boot Socket Server Application
 *
 * 이 애플리케이션은 ByteBuddy Agent에 의해 자동 계측됩니다.
 * SocketHandler.handle() 메서드에 수동으로 코드를 추가하지 않아도,
 * Agent가 자동으로 traceparent를 추출하고 Context를 ThreadLocal에 저장합니다.
 */
@SpringBootApplication
public class SpringBootSocketApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootSocketApplication.class, args);
    }

    @Bean
    public SocketServer socketServer(ExecutorService executorService, SocketHandler socketHandler) {
        return new SocketServer(executorService, socketHandler);
    }

    @Bean
    public ExecutorService executorService() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r);
            thread.setName("socket-server-thread-" + thread.getId());
            return thread;
        });
    }

    @Bean
    public SocketHandler socketHandler() {
        return new SocketHandler();
    }
}
