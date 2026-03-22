package com.javaagent.server.config;

import com.javaagent.commons.AgentConstants;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Configuration - loaded from application.properties
 * Uses AgentConstants for default values
 */
@Configuration
public class AgentConfiguration implements InitializingBean {

    // Arthas Configuration
    @Value("${" + AgentConstants.PROP_ARTHAS_HOST + ":" + AgentConstants.DEFAULT_ARTHAS_HOST + "}")
    private String arthasHost;

    @Value("${" + AgentConstants.PROP_ARTHAS_PORT + ":" + AgentConstants.DEFAULT_ARTHAS_PORT + "}")
    private int arthasPort;

    @Value("${" + AgentConstants.PROP_ARTHAS_HOME + ":#{null}}")
    private String arthasHome;

    // OpenSearch Configuration
    @Value("${" + AgentConstants.PROP_OPENSEARCH_HOST + ":" + AgentConstants.DEFAULT_OPENSEARCH_HOST + "}")
    private String opensearchHost;

    @Value("${" + AgentConstants.PROP_OPENSEARCH_INDEX + ":" + AgentConstants.DEFAULT_OPENSEARCH_INDEX + "}")
    private String opensearchIndexPattern;

    // OpenTelemetry Configuration
    @Value("${" + AgentConstants.PROP_OTEL_SERVICE_NAME + ":" + AgentConstants.DEFAULT_OTEL_SERVICE_NAME + "}")
    private String otelServiceName;

    @Value("${" + AgentConstants.PROP_JAEGER_ENDPOINT + ":" + AgentConstants.DEFAULT_JAEGER_ENDPOINT + "}")
    private String jaegerEndpoint;

    private static AgentConfiguration instance;

    @Override
    public void afterPropertiesSet() {
        instance = this;
        System.out.println("[AgentConfiguration] Configuration loaded:");
        System.out.println("  Arthas: " + arthasHost + ":" + arthasPort);
        System.out.println("  OpenSearch: " + opensearchHost + " (" + opensearchIndexPattern + ")");
        System.out.println("  OpenTelemetry: " + otelServiceName + " -> " + jaegerEndpoint);

        // Configure ArthasManager
        try {
            com.javaagent.arthas.ArthasManager.configureTunnelServer(arthasHost, arthasPort);
            if (arthasHome != null && !arthasHome.isEmpty()) {
                System.setProperty(AgentConstants.PROP_ARTHAS_HOME, arthasHome);
            }
        } catch (Exception e) {
            System.err.println("[AgentConfiguration] Failed to configure ArthasManager: " + e.getMessage());
        }

        // Configure OpenSearchManager
        try {
            com.javaagent.server.opensearch.OpenSearchManager.configure(opensearchHost, opensearchIndexPattern);
        } catch (Exception e) {
            System.err.println("[AgentConfiguration] Failed to configure OpenSearchManager: " + e.getMessage());
        }
    }

    // Static getters
    public static String getArthasHost() {
        return instance != null ? instance.arthasHost : AgentConstants.DEFAULT_ARTHAS_HOST;
    }

    public static int getArthasPort() {
        return instance != null ? instance.arthasPort : AgentConstants.DEFAULT_ARTHAS_PORT;
    }

    public static String getOpensearchHost() {
        return instance != null ? instance.opensearchHost : AgentConstants.DEFAULT_OPENSEARCH_HOST;
    }

    public static String getOpensearchIndexPattern() {
        return instance != null ? instance.opensearchIndexPattern : AgentConstants.DEFAULT_OPENSEARCH_INDEX;
    }
}
