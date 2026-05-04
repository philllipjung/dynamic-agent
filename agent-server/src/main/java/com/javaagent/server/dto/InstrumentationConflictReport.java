package com.javaagent.server.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Instrumentation Conflict Report
 *
 * Reports overlapping instrumentation between:
 * - OpenTelemetry Java Agent (auto-instrumentation)
 * - ByteBuddy Agent (manual instrumentation)
 * - Arthas (watch/trace commands)
 */
public class InstrumentationConflictReport {

    private String pid;
    private String className;
    private ConflictLevel overallLevel;
    private List<ConflictItem> conflicts;
    private List<String> recommendations;
    private String generatedAt;

    public enum ConflictLevel {
        NONE("No conflicts detected"),
        LOW("Minor overlap, may cause duplicate spans"),
        MEDIUM("Significant overlap, likely conflicts"),
        HIGH("Severe overlap, high risk of conflicts");

        private final String description;

        ConflictLevel(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public static class ConflictItem {
        private String agentType;  // "OPENTELEMETRY", "BYTEBUDDY", "ARTHAS"
        private String scope;       // "class", "method", "package"
        private String target;      // e.g., "com.example.service.OrderService"
        private String reason;      // Why it's a conflict
        private String suggestion;  // How to resolve

        public ConflictItem() {}

        public ConflictItem(String agentType, String scope, String target, String reason, String suggestion) {
            this.agentType = agentType;
            this.scope = scope;
            this.target = target;
            this.reason = reason;
            this.suggestion = suggestion;
        }

        public String getAgentType() { return agentType; }
        public void setAgentType(String agentType) { this.agentType = agentType; }

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    }

    public InstrumentationConflictReport() {
        this.conflicts = new ArrayList<>();
        this.recommendations = new ArrayList<>();
        this.generatedAt = java.time.LocalDateTime.now().toString();
    }

    public String getPid() { return pid; }
    public void setPid(String pid) { this.pid = pid; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public ConflictLevel getOverallLevel() { return overallLevel; }
    public void setOverallLevel(ConflictLevel overallLevel) { this.overallLevel = overallLevel; }

    public List<ConflictItem> getConflicts() { return conflicts; }
    public void setConflicts(List<ConflictItem> conflicts) { this.conflicts = conflicts; }

    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public void addConflict(ConflictItem conflict) {
        this.conflicts.add(conflict);
    }

    public void addRecommendation(String recommendation) {
        this.recommendations.add(recommendation);
    }

    public void calculateOverallLevel() {
        if (conflicts.isEmpty()) {
            this.overallLevel = ConflictLevel.NONE;
        } else if (conflicts.stream().anyMatch(c -> c.agentType.equals("OPENTELEMETRY") && c.agentType.equals("BYTEBUDDY"))) {
            this.overallLevel = ConflictLevel.HIGH;
        } else if (conflicts.size() > 5) {
            this.overallLevel = ConflictLevel.MEDIUM;
        } else {
            this.overallLevel = ConflictLevel.LOW;
        }
    }
}
