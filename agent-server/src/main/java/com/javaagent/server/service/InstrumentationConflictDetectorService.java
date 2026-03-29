package com.javaagent.server.service;

import com.javaagent.server.dto.InstrumentationConflictReport;
import com.javaagent.server.dto.OpenTelemetryPatterns;
import com.javaagent.server.dto.AgentStateResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for detecting conflicts between OpenTelemetry, ByteBuddy, and Arthas instrumentation
 */
@Service
public class InstrumentationConflictDetectorService {

    @Autowired
    private AgentStateService agentStateService;

    /**
     * Check if a specific instrumentation would conflict
     */
    public Map<String, Object> checkInstrumentationConflict(String pid, String className, String methodName) {
        Map<String, Object> result = new LinkedHashMap<>();

        boolean hasConflict = OpenTelemetryPatterns.isOtelAutoInstrumented(className, methodName);

        result.put("className", className);
        result.put("methodName", methodName);
        result.put("hasConflict", hasConflict);

        if (hasConflict) {
            result.put("conflictType", "OPENTELEMETRY");
            result.put("reason", OpenTelemetryPatterns.getOtelScopeReason(className, methodName));
            result.put("recommendation", "Skip instrumentation, OpenTelemetry will cover this");
        } else {
            result.put("conflictType", "NONE");
            result.put("recommendation", "Safe to instrument with ByteBuddy");
        }

        return result;
    }

    /**
     * Generate a conflict report for a specific PID
     */
    public InstrumentationConflictReport generateConflictReport(String pid) {
        InstrumentationConflictReport report = new InstrumentationConflictReport();
        report.setPid(pid);

        // Get current instrumentations
        List<AgentStateResponse.InstrumentationState> instrumentations =
            agentStateService.getInstrumentations(pid);

        // Analyze each instrumentation
        for (AgentStateResponse.InstrumentationState inst : instrumentations) {
            String className = inst.getClassName();
            String methodName = inst.getMethodName();
            String adviceType = inst.getAdviceType();

            // Check for OpenTelemetry overlap
            if (OpenTelemetryPatterns.isOtelAutoInstrumented(className, methodName)) {
                InstrumentationConflictReport.ConflictItem conflict = new InstrumentationConflictReport.ConflictItem(
                    "OPENTELEMETRY_" + adviceType,
                    "method",
                    className + "." + methodName,
                    OpenTelemetryPatterns.getOtelScopeReason(className, methodName),
                    "Remove " + adviceType + " instrumentation, rely on OpenTelemetry"
                );
                report.addConflict(conflict);
            }
        }

        // Check for duplicates between ByteBuddy and Arthas
        Set<String> byteBuddyTargets = instrumentations.stream()
            .filter(inst -> "SPAN".equals(inst.getAdviceType()) || "SPAN_ATTRIBUTE".equals(inst.getAdviceType()))
            .map(inst -> inst.getClassName() + "." + inst.getMethodName())
            .collect(Collectors.toSet());

        Set<String> arthasTargets = instrumentations.stream()
            .filter(inst -> "ARTHAS_WATCH".equals(inst.getAdviceType()) || "ARTHAS_TRACE".equals(inst.getAdviceType()))
            .map(inst -> inst.getClassName() + "." + inst.getMethodName())
            .collect(Collectors.toSet());

        // Find overlaps
        Set<String> overlaps = new HashSet<>(byteBuddyTargets);
        overlaps.retainAll(arthasTargets);

        for (String overlap : overlaps) {
            InstrumentationConflictReport.ConflictItem conflict = new InstrumentationConflictReport.ConflictItem(
                "BYTEBUDDY_ARTHAS",
                "method",
                overlap,
                "Both ByteBuddy and Arthas are monitoring this method",
                "Choose one: use ByteBuddy for spans, or Arthas for debugging"
            );
            report.addConflict(conflict);
        }

        // Generate recommendations
        generateRecommendations(report, instrumentations);

        // Calculate overall level
        report.calculateOverallLevel();

        return report;
    }

    /**
     * Generate recommendations based on conflicts
     */
    private void generateRecommendations(InstrumentationConflictReport report,
                                        List<AgentStateResponse.InstrumentationState> instrumentations) {
        long conflictCount = report.getConflicts().size();

        if (conflictCount == 0) {
            report.addRecommendation("No conflicts detected. Current instrumentation is safe.");
            return;
        }

        // Count by conflict type
        Map<String, Long> conflictTypes = report.getConflicts().stream()
            .collect(Collectors.groupingBy(
                c -> c.getAgentType().split("_")[0],  // Get first part (OPENTELEMETRY, BYTEBUDDY, etc.)
                Collectors.counting()
            ));

        if (conflictTypes.getOrDefault("OPENTELEMETRY", 0L) > 0) {
            report.addRecommendation("Remove OpenTelemetry-overlapping instrumentation to avoid duplicate spans.");
            report.addRecommendation("Focus ByteBuddy instrumentation on @Service business logic methods.");
        }

        if (conflictTypes.getOrDefault("BYTEBUDDY", 0L) > 0
            && conflictTypes.getOrDefault("ARTHAS", 0L) > 0) {
            report.addRecommendation("Choose between ByteBuddy (for production spans) and Arthas (for debugging).");
            report.addRecommendation("Avoid using both on the same methods.");
        }

        if (conflictCount > 5) {
            report.addRecommendation("High number of conflicts detected. Review instrumentation strategy.");
        }
    }
}
