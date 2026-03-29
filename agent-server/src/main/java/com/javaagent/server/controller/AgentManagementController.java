package com.javaagent.server.controller;

import com.javaagent.server.dto.AgentStateResponse;
import com.javaagent.server.service.AgentStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API for Agent Management
 * Provides endpoints to query JVM and instrumentation state
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentManagementController {

    @Autowired
    private AgentStateService agentStateService;

    /**
     * Get all attached JVMs
     * GET /api/agent/jvms
     *
     * Returns list of all JVMs that have agents attached
     */
    @GetMapping("/jvms")
    public Map<String, Object> getAllJvms() {
        try {
            List<AgentStateResponse.JvmState> jvms = agentStateService.getAllJvms();

            return Map.of(
                "success", true,
                "jvms", jvms,
                "count", jvms.size()
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "jvms", List.of(),
                "count", 0
            );
        }
    }

    /**
     * Get specific JVM details
     * GET /api/agent/jvms/{pid}
     *
     * Returns detailed information about a specific JVM including its instrumentations
     */
    @GetMapping("/jvms/{pid}")
    public Map<String, Object> getJvmDetail(@PathVariable("pid") String pid) {
        try {
            AgentStateResponse.JvmState jvm = agentStateService.getJvm(pid);

            if (jvm == null) {
                return Map.of(
                    "success", false,
                    "error", "JVM not found: " + pid
                );
            }

            List<AgentStateResponse.InstrumentationState> instrumentations =
                agentStateService.getInstrumentations(pid);

            return Map.of(
                "success", true,
                "jvm", jvm,
                "instrumentations", instrumentations,
                "instrumentationCount", instrumentations.size()
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }

    /**
     * Get instrumentations for a JVM
     * GET /api/agent/instrumentations/{pid}
     *
     * Returns all instrumentations (advice) applied to the specified JVM
     */
    @GetMapping("/instrumentations/{pid}")
    public Map<String, Object> getInstrumentations(@PathVariable("pid") String pid) {
        try {
            List<AgentStateResponse.InstrumentationState> instrumentations =
                agentStateService.getInstrumentations(pid);

            return Map.of(
                "success", true,
                "pid", pid,
                "instrumentations", instrumentations,
                "count", instrumentations.size()
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "pid", pid,
                "instrumentations", List.of(),
                "count", 0
            );
        }
    }

    /**
     * Get events for a specific PID
     * GET /api/agent/events?pid=18508&limit=50
     *
     * Returns event history for a specific JVM
     */
    @GetMapping("/events")
    public Map<String, Object> getEvents(
            @RequestParam(value = "pid", required = false) String pid,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            List<Map<String, Object>> events;

            if (pid != null && !pid.isEmpty()) {
                events = agentStateService.getEvents(pid, limit);
            } else {
                events = agentStateService.getAllEvents(limit);
            }

            return Map.of(
                "success", true,
                "events", events,
                "count", events.size(),
                "pid", pid != null ? pid : "all"
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "events", List.of(),
                "count", 0
            );
        }
    }

    /**
     * Detach all JVMs
     * POST /api/agent/detach-all
     *
     * Detaches all attached JVMs and clears their state from Redis
     */
    @PostMapping("/detach-all")
    public Map<String, Object> detachAll() {
        try {
            List<AgentStateResponse.JvmState> jvms = agentStateService.getAllJvms();
            int detachedCount = 0;
            List<String> errors = new java.util.ArrayList<>();

            for (AgentStateResponse.JvmState jvm : jvms) {
                try {
                    agentStateService.removeJvm(jvm.getPid(), jvm.getAgentType());
                    detachedCount++;
                } catch (Exception e) {
                    errors.add("Failed to detach " + jvm.getPid() + ": " + e.getMessage());
                }
            }

            return Map.of(
                "success", true,
                "message", "Detached " + detachedCount + " JVM(s)",
                "detachedCount", detachedCount,
                "totalCount", jvms.size(),
                "errors", errors
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "detachedCount", 0
            );
        }
    }

    /**
     * Detach all JVMs of specific agent type
     * POST /api/agent/detach-all?agentType=BYTEBUDDY
     *
     * Detaches all JVMs with the specified agent type
     */
    @PostMapping("/detach-all-by-type")
    public Map<String, Object> detachAllByType(@RequestParam("agentType") String agentType) {
        try {
            List<AgentStateResponse.JvmState> allJvms = agentStateService.getAllJvms();
            List<AgentStateResponse.JvmState> matchingJvms = new java.util.ArrayList<>();
            int detachedCount = 0;
            List<String> errors = new java.util.ArrayList<>();

            // Filter by agent type (including BOTH)
            for (AgentStateResponse.JvmState jvm : allJvms) {
                if (jvm.getAgentType().equals(agentType) || "BOTH".equals(jvm.getAgentType())) {
                    matchingJvms.add(jvm);
                }
            }

            // Detach matching JVMs
            for (AgentStateResponse.JvmState jvm : matchingJvms) {
                try {
                    agentStateService.removeJvm(jvm.getPid(), agentType);
                    detachedCount++;
                } catch (Exception e) {
                    errors.add("Failed to detach " + jvm.getPid() + ": " + e.getMessage());
                }
            }

            return Map.of(
                "success", true,
                "message", "Detached " + detachedCount + " JVM(s) with agent type " + agentType,
                "detachedCount", detachedCount,
                "matchedCount", matchingJvms.size(),
                "agentType", agentType,
                "errors", errors
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "detachedCount", 0
            );
        }
    }
}
