package com.javaagent.server.filter;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Example Spring Filter for demonstrating EventAdvice
 *
 * This filter demonstrates:
 * 1. How to create a Spring Filter
 * 2. How EventAdvice captures request/response data
 * 3. How to view captured events via /api/events/display
 *
 * Usage:
 * 1. Attach ByteBuddy agent with EventAdvice to this filter's doFilter method
 * 2. Make HTTP requests to any endpoint
 * 3. View captured events at GET /api/events/display
 *
 * Example command to attach EventAdvice:
 * POST /api/bytebuddy/createSpanAttribute
 * {
 *   "className": "com.javaagent.server.filter.ExampleFilter",
 *   "methodName": "doFilterInternal",
 *   "parameters": ["request", "response", "filterChain"]
 * }
 */
@Component
public class ExampleFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Log request info (before filter chain)
        System.out.println("[ExampleFilter] Request: " + request.getMethod() + " " + request.getRequestURI());

        // Continue filter chain
        filterChain.doFilter(request, response);

        // Log response info (after filter chain)
        System.out.println("[ExampleFilter] Response: Status=" + response.getStatus());
    }
}
