package com.javaagent.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.arthas.ArthasManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for real-time agent communication
 */
@SuppressWarnings("unchecked")
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        sendMessage(session, Map.of(
                "type", "connected",
                "sessionId", session.getId(),
                "message", "WebSocket connection established"
        ));
        System.out.println("[WebSocket] Client connected: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        Map<String, Object> request = objectMapper.readValue(payload, Map.class);

        String action = (String) request.get("action");
        Map<String, Object> response;

        switch (action) {
            case "ping":
                response = Map.of("type", "pong", "timestamp", System.currentTimeMillis());
                break;
            case "arthas_trace":
                response = handleArthasTrace(request);
                break;
            case "arthas_stack":
                response = handleArthasStack(request);
                break;
            case "arthas_watch":
                response = handleArthasWatch(request);
                break;
            default:
                response = Map.of("type", "error", "message", "Unknown action: " + action);
                break;
        }

        sendMessage(session, response);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        System.out.println("[WebSocket] Client disconnected: " + session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        System.err.println("[WebSocket] Transport error: " + exception.getMessage());
    }

    /**
     * Broadcast message to all connected clients
     */
    public void broadcast(Map<String, Object> message) {
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
            sessions.values().forEach(session -> {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(textMessage);
                    }
                } catch (IOException e) {
                    System.err.println("[WebSocket] Broadcast error: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            System.err.println("[WebSocket] Broadcast serialization error: " + e.getMessage());
        }
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            System.err.println("[WebSocket] Send error: " + e.getMessage());
        }
    }

    private Map<String, Object> handleArthasTrace(Map<String, Object> request) {
        String className = (String) request.get("className");
        String methodName = (String) request.get("methodName");

        String result = ArthasManager.trace(className, methodName);

        return Map.of(
                "type", "arthas_trace_response",
                "success", result.startsWith("SUCCESS") || !result.startsWith("ERROR"),
                "message", result,
                "output", result
        );
    }

    private Map<String, Object> handleArthasStack(Map<String, Object> request) {
        String className = (String) request.get("className");
        String methodName = (String) request.get("methodName");

        String result = ArthasManager.stack(className, methodName);

        return Map.of(
                "type", "arthas_stack_response",
                "success", result.startsWith("SUCCESS") || !result.startsWith("ERROR"),
                "message", result,
                "output", result
        );
    }

    private Map<String, Object> handleArthasWatch(Map<String, Object> request) {
        String className = (String) request.get("className");
        String methodName = (String) request.get("methodName");
        String expression = (String) request.getOrDefault("expression", "{params, returnObj}");
        int limit = request.containsKey("limit") ? Integer.parseInt(request.get("limit").toString()) : 5;

        String result = ArthasManager.watch(className, methodName, expression, limit);

        return Map.of(
                "type", "arthas_watch_response",
                "success", result.startsWith("SUCCESS") || !result.startsWith("ERROR"),
                "message", result,
                "expression", expression,
                "limit", limit,
                "output", result
        );
    }
}
