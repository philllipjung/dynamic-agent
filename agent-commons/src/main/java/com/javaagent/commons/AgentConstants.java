package com.javaagent.commons;

/**
 * Common constants for Java Agent System
 * Prevents duplication of default values across modules
 */
public final class AgentConstants {

    private AgentConstants() {}

    // Arthas
    public static final String DEFAULT_ARTHAS_HOST = "127.0.0.1";
    public static final int DEFAULT_ARTHAS_PORT = 8563;

    // OpenSearch
    public static final String DEFAULT_OPENSEARCH_HOST = "http://localhost:9200";
    public static final String DEFAULT_OPENSEARCH_INDEX = "jaeger-span-*";

    // Redis
    public static final String DEFAULT_REDIS_HOST = "localhost";
    public static final int DEFAULT_REDIS_PORT = 6379;

    // OpenTelemetry / Jaeger
    public static final String DEFAULT_JAEGER_ENDPOINT = "http://localhost:14250";
    public static final String DEFAULT_OTEL_SERVICE_NAME = "java-agent";

    // System Property Keys
    public static final String PROP_ARTHAS_HOST = "arthas.tunnel.server.host";
    public static final String PROP_ARTHAS_PORT = "arthas.tunnel.server.port";
    public static final String PROP_ARTHAS_HOME = "arthas.home";

    public static final String PROP_OPENSEARCH_HOST = "opensearch.host";
    public static final String PROP_OPENSEARCH_INDEX = "opensearch.index.pattern";

    public static final String PROP_REDIS_HOST = "spring.redis.host";
    public static final String PROP_REDIS_PORT = "spring.redis.port";

    public static final String PROP_OTEL_SERVICE_NAME = "opentelemetry.service.name";
    public static final String PROP_JAEGER_ENDPOINT = "opentelemetry.exporter.jaeger.endpoint";

    // ByteBuddy Helper Classes (only helpers that need injection, NOT Advice classes)
    public static final String[] HELPER_CLASSES = {
        "com.javaagent.bytebuddy.helper.SpanAttributeHelper",
        "com.javaagent.bytebuddy.helper.SpanHelper",
        "com.javaagent.bytebuddy.helper.JaegerLinkLookupService",
        "com.javaagent.bytebuddy.helper.ThreadNameHelper",
        "com.javaagent.bytebuddy.advices.KernelAdvice",
        "com.javaagent.bytebuddy.helper.KernelHelper"
    };

    // Advice JAR name (for dynamic loading scenarios)
    public static final String ADVICE_JAR_NAME = "bytebuddy-advice-1.0.0.jar";
}
