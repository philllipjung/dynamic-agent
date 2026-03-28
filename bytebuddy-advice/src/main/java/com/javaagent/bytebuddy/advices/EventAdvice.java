package com.javaagent.bytebuddy.advices;

import com.javaagent.bytebuddy.helper.EventHelper;
import net.bytebuddy.asm.Advice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring Event Capture Advice
 *
 * DispatcherServlet.doService() 메서드를 계측하여
 * HTTP 요청의 헤더와 바디를 JSON 형식으로 출력합니다.
 */
public class EventAdvice {

    @Advice.OnMethodEnter(inline = true)
    public static void onMethodEnter(
            @Advice.Origin String method,
            @Advice.Argument(0) Object request
    ) {
        // HttpServletRequest인지 확인
        if (request instanceof HttpServletRequest) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;

            try {
                // 요청 정보 출력
                EventHelper.printRequestInfo(httpRequest);

                // 헤더 출력
                EventHelper.printRequestHeaders(httpRequest);

                // 바디 출력
                EventHelper.printRequestBody(httpRequest);

            } catch (Exception e) {
                System.err.println("{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
}
