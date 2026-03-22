package com.javaagent.bytebuddy.advices;

import com.javaagent.bytebuddy.helper.EventHelper;
import net.bytebuddy.asm.Advice;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.FilterChain;
import java.lang.reflect.Method;

/**
 * EventAdvice - Intercepts Spring Filter's doFilter method to capture HTTP events
 *
 * Captures:
 * - Request headers and body
 * - Response headers and body
 * - Stores events in EventHelper for UI display
 *
 * Usage:
 * Apply this advice to any Spring Filter's doFilter method:
 * - OncePerRequestFilter
 * - GenericFilterBean
 * - Custom filters implementing javax.servlet.Filter
 */
public class EventAdvice {
    private static final ThreadLocal<EventHelper> helperHolder = new ThreadLocal<>();

    /**
     * Intercept on doFilter method entry
     * Captures request information before filter chain execution
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onMethodEnter(
            @Advice.Origin Method method,
            @Advice.Argument(0) ServletRequest request,
            @Advice.Argument(1) ServletResponse response
    ) {
        try {
            // Only process HTTP requests
            if (!(request instanceof HttpServletRequest) ||
                !(response instanceof HttpServletResponse)) {
                return;
            }

            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;

            // Create EventHelper and capture request
            EventHelper helper = new EventHelper(httpRequest, httpResponse);
            helper.captureRequest();
            helperHolder.set(helper);

            System.out.println("[EventAdvice] Captured request: " +
                httpRequest.getMethod() + " " + httpRequest.getRequestURI());

        } catch (Exception e) {
            System.err.println("[EventAdvice] Error in onMethodEnter: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Intercept on doFilter method exit
     * Captures response information after filter chain execution
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMethodExit(
            @Advice.Argument(0) ServletRequest request,
            @Advice.Argument(1) ServletResponse response,
            @Advice.Thrown Throwable throwable
    ) {
        try {
            EventHelper helper = helperHolder.get();
            if (helper == null) {
                return;
            }

            // Only process HTTP responses
            if (!(response instanceof HttpServletResponse)) {
                return;
            }

            HttpServletResponse httpResponse = (HttpServletResponse) response;

            // Capture response and save
            helper.captureResponse();
            helper.save();

            System.out.println("[EventAdvice] Captured response: " +
                "Status=" + httpResponse.getStatus() +
                ", EventID=" + EventHelper.getCurrentEventId());

        } catch (Exception e) {
            System.err.println("[EventAdvice] Error in onMethodExit: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    /**
     * Clean up thread local storage
     */
    private static void cleanup() {
        helperHolder.remove();
    }

    /**
     * Get current EventHelper for this thread
     */
    public static EventHelper getCurrentHelper() {
        return helperHolder.get();
    }
}
