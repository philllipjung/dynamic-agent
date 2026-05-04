package com.example.server1.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Test controller for demonstrating Arthas trace/stack/watch commands
 * All methods are synchronous with blocking operations
 */
@RestController
public class TraceTestController {

    private static final Logger log = LoggerFactory.getLogger(TraceTestController.class);

    @GetMapping("/api/traceTest")
    public String traceTest(@RequestParam(defaultValue = "1") int iterations) {
        log.info("traceTest called with iterations={}", iterations);

        // Perform some synchronous operations
        String result = processRequest(iterations);

        return "Result: " + result;
    }

    @GetMapping("/api/stackTest")
    public String stackTest(@RequestParam(defaultValue = "test") String input) {
        log.info("stackTest called with input={}", input);

        // Create a call chain
        String step1 = preprocessInput(input);
        String step2 = processInput(step1);
        String step3 = postprocessInput(step2);

        return "Final: " + step3;
    }

    @GetMapping("/api/watchTest")
    public UserInfo watchTest(@RequestParam(defaultValue = "100") int userId) {
        log.info("watchTest called with userId={}", userId);

        UserInfo user = findUser(userId);
        UserInfo enriched = enrichUser(user);

        return enriched;
    }

    @GetMapping("/api/test")
    public String test(@RequestParam(defaultValue = "default-session") String sessionId,
                       @RequestParam(defaultValue = "100") String userId,
                       @RequestParam(defaultValue = "5000") String orderId) {
        log.info("test called with sessionId={}, userId={}, orderId={}", sessionId, userId, orderId);

        // Call the processSession method with all parameters
        String result = processSession(sessionId, userId, orderId);

        return "Result: " + result;
    }

    // Helper methods to create trace depth
    private String processRequest(int iterations) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < iterations; i++) {
            sb.append(doWork(i));
        }

        addMetadata(sb);

        return sb.toString();
    }

    private String doWork(int index) {
        // Simulate some work
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return "Item-" + index + "-";
    }

    private void addMetadata(StringBuilder sb) {
        sb.append("Metadata-");
        sb.append(System.currentTimeMillis());
    }

    private String preprocessInput(String input) {
        return "[" + input.toUpperCase() + "]";
    }

    private String processInput(String input) {
        return input + "-PROCESSED";
    }

    private String postprocessInput(String input) {
        return input + "-DONE";
    }

    private UserInfo findUser(int userId) {
        return new UserInfo(userId, "User" + userId, "user" + userId + "@example.com");
    }

    private UserInfo enrichUser(UserInfo user) {
        user.setRole("ADMIN");
        user.setActive(true);
        return user;
    }

    /**
     * Test method for demonstrating Arthas watch
     * Generates random values and returns the sessionId
     */
    public String processSession(String sessionId, String userId, String orderId) {
        // In a real scenario, these would be processed
        // For now, we just return the sessionId
        return sessionId;
    }

    // Simple POJO for testing watch command
    public static class UserInfo {
        private int id;
        private String name;
        private String email;
        private String role;
        private boolean active;

        public UserInfo(int id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        // Getters and setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        @Override
        public String toString() {
            return "UserInfo{id=" + id + ", name='" + name + "', email='" + email + "', role='" + role + "', active=" + active + "}";
        }
    }
}
