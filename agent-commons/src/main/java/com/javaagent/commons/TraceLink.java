package com.javaagent.commons;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * OpenSearch에서 조회한 Trace 연결 정보
 */
public class TraceLink implements Serializable {

    private static final long serialVersionUID = 1L;

    private String traceID;
    private String spanID;
    private String operationName;
    private Map<String, String> attributes;

    public TraceLink() {
    }

    public TraceLink(String traceID, String spanID, String operationName) {
        this.traceID = traceID;
        this.spanID = spanID;
        this.operationName = operationName;
    }

    // Getters and Setters
    public String getTraceID() { return traceID; }
    public TraceLink setTraceID(String traceID) {
        this.traceID = traceID;
        return this;
    }

    public String getSpanID() { return spanID; }
    public TraceLink setSpanID(String spanID) {
        this.spanID = spanID;
        return this;
    }

    public String getOperationName() { return operationName; }
    public TraceLink setOperationName(String operationName) {
        this.operationName = operationName;
        return this;
    }

    public Map<String, String> getAttributes() { return attributes; }
    public TraceLink setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TraceLink traceLink = (TraceLink) o;
        return Objects.equals(traceID, traceLink.traceID) &&
                Objects.equals(spanID, traceLink.spanID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(traceID, spanID);
    }

    @Override
    public String toString() {
        return "TraceLink{" +
                "traceID='" + traceID + '\'' +
                ", spanID='" + spanID + '\'' +
                ", operationName='" + operationName + '\'' +
                '}';
    }
}
