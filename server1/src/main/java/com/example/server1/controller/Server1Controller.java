package com.example.server1.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
public class Server1Controller {

    private static final Logger log = LoggerFactory.getLogger(Server1Controller.class);
    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final WebClient webClient;

    public Server1Controller() {
        this.webClient = WebClient.builder()
                .baseUrl("http://localhost:8082")
                .build();
    }

    @GetMapping("/api/hello")
    public Mono<String> hello(
            @RequestHeader(value = SESSION_ID_HEADER, required = false) String sessionIdHeader) {

        // Generate Request ID
        String requestId = UUID.randomUUID().toString();
        final String sessionId = (sessionIdHeader == null || sessionIdHeader.isEmpty())
                ? UUID.randomUUID().toString()
                : sessionIdHeader;

        // Add to MDC for JSON logging
        MDC.put("requestId", requestId);
        MDC.put("sessionId", sessionId);

        try {
            log.info("hello webflux");

            return webClient.get()
                    .uri("/api/hello")
                    .headers(headers -> {
                        // Add Request ID and Session ID headers
                        headers.add(REQUEST_ID_HEADER, requestId);
                        headers.add(SESSION_ID_HEADER, sessionId);
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(response -> "Hello from Server1 -> " + response)
                    .doOnError(ex -> log.error("Error in Server1: {}", ex.getMessage()));
        } finally {
            // Clear MDC after request processing
            MDC.remove("requestId");
            MDC.remove("sessionId");
        }
    }
}
