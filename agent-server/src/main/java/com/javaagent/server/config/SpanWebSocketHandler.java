package com.javaagent.server.config;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket Handler for real-time Span events
 * 
 * Clients can connect to ws://localhost:8080/ws/spans
 * to receive real-time span creation events
 */
public class SpanWebSocketHandler extends TextWebSocketHandler {

    private static final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("[WebSocket] Client connected: " + session.getId() + 
                         " (Total: " + sessions.size() + ")");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        sessions.remove(session);
        System.out.println("[WebSocket] Client disconnected: " + session.getId() + 
                         " (Total: " + sessions.size() + ")");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Handle incoming messages from clients if needed
        String payload = message.getPayload();
        System.out.println("[WebSocket] Received message: " + payload);
    }

    /**
     * Broadcast span event to all connected clients
     * 
     * @param event JSON event string
     */
    public static void broadcastSpanEvent(String event) {
        TextMessage message = new TextMessage(event);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    System.err.println("[WebSocket] Failed to send message: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Get number of connected clients
     */
    public static int getConnectionCount() {
        return sessions.size();
    }
}
