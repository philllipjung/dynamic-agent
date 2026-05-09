package com.socket.context;

/**
 * Trace Context 보관함
 *
 * 소켓에서 추출한 traceparent를 저장하여
 * SocketHandler에서 사용할 수 있게 합니다.
 */
public class TraceContextHolder {

    private static final ThreadLocal<String> traceParentHolder = new ThreadLocal<>();

    /**
     * traceparent 저장
     */
    public static void setTraceParent(String traceParent) {
        traceParentHolder.set(traceParent);
    }

    /**
     * traceparent 조회
     */
    public static String getTraceParent() {
        return traceParentHolder.get();
    }

    /**
     * 초기화
     */
    public static void clear() {
        traceParentHolder.remove();
    }
}
