package com.javaagent.bytebuddy.helper;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HTTP Event Helper
 *
 * HttpServletRequest의 정보를 JSON 형식으로 출력합니다.
 */
public class EventHelper {

    private static final int MAX_BODY_LENGTH = 10000; // 최대 10,000字符까지만 출력

    /**
     * HttpServletRequest의 모든 헤더를 JSON 형식으로 출력
     */
    public static void printRequestHeaders(HttpServletRequest request) {
        System.err.println("{\"headers\":{");

        List<String> headerNames = Collections.list(request.getHeaderNames());
        if (headerNames.isEmpty()) {
            System.err.println("}");
            return;
        }

        for (int i = 0; i < headerNames.size(); i++) {
            String headerName = headerNames.get(i);
            List<String> headerValues = Collections.list(request.getHeaders(headerName));

            System.err.print("  \"" + headerName + "\":");
            if (headerValues.size() == 1) {
                System.err.print("\"" + escapeJson(headerValues.get(0)) + "\"");
            } else {
                System.err.print("[");
                for (int j = 0; j < headerValues.size(); j++) {
                    System.err.print("\"" + escapeJson(headerValues.get(j)) + "\"");
                    if (j < headerValues.size() - 1) System.err.print(", ");
                }
                System.err.print("]");
            }

            if (i < headerNames.size() - 1) {
                System.err.println(",");
            } else {
                System.err.println();
            }
        }

        System.err.println("}}");
        System.err.flush();
    }

    /**
     * HttpServletRequest의 바디를 JSON 형식으로 출력
     */
    public static void printRequestBody(HttpServletRequest request) {
        System.err.print("{\"body\":\"");

        try {
            // 바디가 있는지 확인
            if (request.getContentLength() <= 0) {
                System.err.println("\"}");
                return;
            }

            // BufferedReader로 본문 읽기 (ServletInputStream 없이도 가능)
            BufferedReader reader = request.getReader();
            String body = reader.lines().collect(Collectors.joining());

            // 너무 길면 자르기
            if (body.length() > MAX_BODY_LENGTH) {
                body = body.substring(0, MAX_BODY_LENGTH) + "... (truncated, total: " + body.length() + " chars)";
            }

            // JSON 이스케이프 처리 후 출력
            System.err.println(escapeJson(body) + "\"}");

        } catch (IllegalStateException e) {
            // getReader()를 호출할 수 없는 경우 (이미 getInputStream()이 호출됨)
            System.err.println("(body already consumed, length: " + request.getContentLength() + " bytes - cannot read again)\"}");
        } catch (Exception e) {
            // 기타 오류
            System.err.println("(error reading body: " + escapeJson(e.getMessage()) + ", length: " + request.getContentLength() + " bytes)\"}");
        }

        System.err.flush();
    }

    /**
     * JSON 문자열 이스케이프
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t");
    }

    /**
     * 요청 정보를 JSON 형식으로 출력
     */
    public static void printRequestInfo(HttpServletRequest request) {
        System.err.println("{");
        System.err.println("  \"method\": \"" + request.getMethod() + "\",");
        System.err.println("  \"uri\": \"" + request.getRequestURI() + "\",");
        System.err.println("  \"queryString\": \"" + (request.getQueryString() != null ? request.getQueryString() : "") + "\",");
        System.err.print("  \"contentType\": \"" + (request.getContentType() != null ? request.getContentType() : "") + "\"");
        System.err.println();
        System.err.println("}");
        System.err.flush();
    }
}
