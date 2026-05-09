package com.socket.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for HTTP Auto-Instrumentation Testing
 *
 * 🎯 100% 자동 계측 - 수동 오텔 코드 없음!
 * OpenTelemetry Java Agent가 모든 추적을 자동으로 처리합니다.
 */
@RestController
public class HelloController {

    /**
     * HTTP 요청 처리 - 순수 비즈니스 로직만!
     *
     * 🤖 OpenTelemetry Java Agent가 자동으로:
     * 1. Span 생성
     * 2. HTTP 헤더에서 traceparent 추출
     * 3. Context 저장 및 전파
     */
    @GetMapping("/hello")
    public String hello() {
        System.out.println("=================================================");
        System.out.println("🌐 HelloController.hello() 호출됨");
        System.out.println("✨ OpenTelemetry Agent가 HTTP를 자동 계측했습니다!");
        System.out.println("=================================================");

        // 내부 메서드 호출
        businessLogic();

        return "Hello!";
    }

    /**
     * 비즈니스 로직 - Trace Context가 자동으로 전파됨
     */
    private void businessLogic() {
        System.out.println("=================================================");
        System.out.println("💼 businessLogic() 호출됨");
        System.out.println("✨ Trace Context가 자동으로 전파되었습니다!");
        System.out.println("=================================================");
    }
}
