package com.javaagent.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.javaagent.server.dto.AgentStateResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent State Service - Manages JVM and instrumentation state in Redis
 *
 * Redis Keys:
 * - jvm:{pid} → JSON string of JvmState
 * - instrumentation:{pid}:{className}:{methodName} → JSON string of InstrumentationState
 * - jvms:all → SET of all PIDs
 * - events:all → LIST of all event JSON strings
 * - events:{pid} → LIST of event JSON strings for specific PID
 */
@Service
public class AgentStateService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String JVM_KEY_PREFIX = "jvm:";
    private static final String INSTRUMENTATION_KEY_PREFIX = "instrumentation:";
    private static final String ALL_JVMS_KEY = "jvms:all";
    private static final String EVENTS_ALL_KEY = "events:all";
    private static final String EVENTS_PID_PREFIX = "events:";

    /**
     * Save JVM state to Redis
     */
    public void saveJvm(String pid, String className, String agentType) {
        try {
            String key = JVM_KEY_PREFIX + pid;
            String attachTime = LocalDateTime.now().format(timeFormatter);

            AgentStateResponse.JvmState jvmState = new AgentStateResponse.JvmState(
                pid, className, agentType, attachTime
            );

            String jsonValue = objectMapper.writeValueAsString(jvmState);
            redisTemplate.opsForValue().set(key, jsonValue);
            redisTemplate.opsForSet().add(ALL_JVMS_KEY, pid);

            System.out.println("[AgentStateService] Saved JVM: " + key + " = " + jsonValue);

            // Log attach event
            logAttachEvent(pid, agentType);

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to save JVM: " + e.getMessage());
        }
    }

    /**
     * Update JVM agent type (e.g., when both ByteBuddy and Arthas are attached)
     */
    public void updateJvmAgentType(String pid, String agentType) {
        try {
            String key = JVM_KEY_PREFIX + pid;
            String jsonValue = redisTemplate.opsForValue().get(key);

            if (jsonValue != null) {
                AgentStateResponse.JvmState jvmState = objectMapper.readValue(
                    jsonValue, AgentStateResponse.JvmState.class);

                // Update agent type to "BOTH" if different
                if (!jvmState.getAgentType().equals(agentType)) {
                    jvmState.setAgentType("BOTH");
                    String updatedJson = objectMapper.writeValueAsString(jvmState);
                    redisTemplate.opsForValue().set(key, updatedJson);
                    System.out.println("[AgentStateService] Updated JVM agent type to BOTH: " + pid);

                    // Log attach event for the new agent type
                    logAttachEvent(pid, agentType);
                }
            }

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to update JVM agent type: " + e.getMessage());
        }
    }

    /**
     * Remove JVM from Redis
     */
    public void removeJvm(String pid) {
        removeJvm(pid, null);
    }

    /**
     * Remove JVM from Redis with agent type for event logging
     */
    public void removeJvm(String pid, String agentType) {
        try {
            // Get agent type from existing JVM state if not provided
            if (agentType == null) {
                String key = JVM_KEY_PREFIX + pid;
                String jsonValue = redisTemplate.opsForValue().get(key);
                if (jsonValue != null) {
                    AgentStateResponse.JvmState jvm = objectMapper.readValue(jsonValue, AgentStateResponse.JvmState.class);
                    agentType = jvm.getAgentType();
                }
            }

            // Remove JVM state
            String key = JVM_KEY_PREFIX + pid;
            redisTemplate.delete(key);

            // Remove from all JVMs set
            redisTemplate.opsForSet().remove(ALL_JVMS_KEY, pid);

            // Remove all instrumentations for this PID
            Set<String> instrumentationKeys = redisTemplate.keys(INSTRUMENTATION_KEY_PREFIX + pid + ":*");
            if (instrumentationKeys != null && !instrumentationKeys.isEmpty()) {
                redisTemplate.delete(instrumentationKeys);
                System.out.println("[AgentStateService] Removed " + instrumentationKeys.size() + " instrumentations for PID " + pid);
            }

            System.out.println("[AgentStateService] Removed JVM: " + pid);

            // Log detach event
            if (agentType != null) {
                logDetachEvent(pid, agentType);
            }

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to remove JVM: " + e.getMessage());
        }
    }

    /**
     * Get JVM by PID
     */
    public AgentStateResponse.JvmState getJvm(String pid) {
        try {
            String key = JVM_KEY_PREFIX + pid;
            String jsonValue = redisTemplate.opsForValue().get(key);

            if (jsonValue == null) {
                return null;
            }

            return objectMapper.readValue(jsonValue, AgentStateResponse.JvmState.class);

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to get JVM: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get all JVMs
     */
    public List<AgentStateResponse.JvmState> getAllJvms() {
        try {
            Set<String> pids = redisTemplate.opsForSet().members(ALL_JVMS_KEY);
            List<AgentStateResponse.JvmState> jvms = new ArrayList<>();

            if (pids != null) {
                for (String pid : pids) {
                    AgentStateResponse.JvmState jvm = getJvm(pid);
                    if (jvm != null) {
                        jvms.add(jvm);
                    }
                }
            }

            return jvms;

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to get all JVMs: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Save instrumentation state
     */
    public void saveInstrumentation(String pid, String className, String methodName, String adviceType) {
        try {
            String key = INSTRUMENTATION_KEY_PREFIX + pid + ":" + className + ":" + methodName;
            String appliedAt = LocalDateTime.now().format(timeFormatter);

            AgentStateResponse.InstrumentationState instState = new AgentStateResponse.InstrumentationState(
                className, methodName, adviceType, appliedAt
            );

            String jsonValue = objectMapper.writeValueAsString(instState);
            redisTemplate.opsForValue().set(key, jsonValue);

            System.out.println("[AgentStateService] Saved instrumentation: " + key + " = " + jsonValue);

            // Log instrumentation event
            logInstrumentationEvent(pid, className, methodName, adviceType);

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to save instrumentation: " + e.getMessage());
        }
    }

    /**
     * Get all instrumentations for a PID
     */
    public List<AgentStateResponse.InstrumentationState> getInstrumentations(String pid) {
        try {
            Set<String> keys = redisTemplate.keys(INSTRUMENTATION_KEY_PREFIX + pid + ":*");
            List<AgentStateResponse.InstrumentationState> instrumentations = new ArrayList<>();

            if (keys != null) {
                for (String key : keys) {
                    String jsonValue = redisTemplate.opsForValue().get(key);
                    if (jsonValue != null) {
                        AgentStateResponse.InstrumentationState inst = objectMapper.readValue(
                            jsonValue, AgentStateResponse.InstrumentationState.class);
                        instrumentations.add(inst);
                    }
                }
            }

            return instrumentations;

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to get instrumentations: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Remove specific instrumentation
     */
    public void removeInstrumentation(String pid, String className, String methodName) {
        try {
            String key = INSTRUMENTATION_KEY_PREFIX + pid + ":" + className + ":" + methodName;
            redisTemplate.delete(key);
            System.out.println("[AgentStateService] Removed instrumentation: " + key);
        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to remove instrumentation: " + e.getMessage());
        }
    }

    // ============================================================
    // Event Logging
    // ============================================================

    /**
     * Log attach event
     */
    public void logAttachEvent(String pid, String agentType) {
        try {
            Map<String, Object> event = Map.of(
                "type", "attach",
                "pid", pid,
                "agentType", agentType,
                "timestamp", LocalDateTime.now().format(timeFormatter)
            );
            String eventJson = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().leftPush(EVENTS_ALL_KEY, eventJson);
            redisTemplate.opsForList().leftPush(EVENTS_PID_PREFIX + pid, eventJson);

            // Keep only last 1000 events globally, 100 per PID
            redisTemplate.opsForList().trim(EVENTS_ALL_KEY, 0, 999);
            redisTemplate.opsForList().trim(EVENTS_PID_PREFIX + pid, 0, 99);

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to log attach event: " + e.getMessage());
        }
    }

    /**
     * Log detach event
     */
    public void logDetachEvent(String pid, String agentType) {
        try {
            Map<String, Object> event = Map.of(
                "type", "detach",
                "pid", pid,
                "agentType", agentType,
                "timestamp", LocalDateTime.now().format(timeFormatter)
            );
            String eventJson = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().leftPush(EVENTS_ALL_KEY, eventJson);
            redisTemplate.opsForList().leftPush(EVENTS_PID_PREFIX + pid, eventJson);

            // Keep only last 1000 events globally, 100 per PID
            redisTemplate.opsForList().trim(EVENTS_ALL_KEY, 0, 999);
            redisTemplate.opsForList().trim(EVENTS_PID_PREFIX + pid, 0, 99);

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to log detach event: " + e.getMessage());
        }
    }

    /**
     * Log instrumentation event
     */
    public void logInstrumentationEvent(String pid, String className, String methodName, String adviceType) {
        try {
            Map<String, Object> event = Map.of(
                "type", "instrumentation",
                "pid", pid,
                "className", className,
                "methodName", methodName,
                "adviceType", adviceType,
                "timestamp", LocalDateTime.now().format(timeFormatter)
            );
            String eventJson = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().leftPush(EVENTS_ALL_KEY, eventJson);
            redisTemplate.opsForList().leftPush(EVENTS_PID_PREFIX + pid, eventJson);

            // Keep only last 1000 events globally, 100 per PID
            redisTemplate.opsForList().trim(EVENTS_ALL_KEY, 0, 999);
            redisTemplate.opsForList().trim(EVENTS_PID_PREFIX + pid, 0, 99);

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to log instrumentation event: " + e.getMessage());
        }
    }

    /**
     * Get events for a specific PID
     */
    public List<Map<String, Object>> getEvents(String pid, int limit) {
        try {
            String key = EVENTS_PID_PREFIX + pid;
            List<String> eventJsons = redisTemplate.opsForList().range(key, 0, limit - 1);

            if (eventJsons == null) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> events = new ArrayList<>();
            for (String eventJson : eventJsons) {
                events.add(objectMapper.readValue(eventJson, new TypeReference<Map<String, Object>>() {}));
            }

            return events;

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to get events: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get all events
     */
    public List<Map<String, Object>> getAllEvents(int limit) {
        try {
            List<String> eventJsons = redisTemplate.opsForList().range(EVENTS_ALL_KEY, 0, limit - 1);

            if (eventJsons == null) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> events = new ArrayList<>();
            for (String eventJson : eventJsons) {
                events.add(objectMapper.readValue(eventJson, new TypeReference<Map<String, Object>>() {}));
            }

            return events;

        } catch (Exception e) {
            System.err.println("[AgentStateService] Failed to get all events: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}
