package com.javaagent.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Parameter Mapping Service - Stores parameter index to name mappings in Redis
 *
 * Uses JSON string storage compatible with bytebuddy-agent's ParameterMappingService
 * Key format: "paramMapping:{className}:{methodName}"
 * Value: JSON string of {"0": "userId", "1": "sessionId"}
 */
@Service
public class ParameterMappingService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY_PREFIX = "paramMapping:";

    /**
     * Save parameter mapping to Redis (JSON string format)
     * Compatible with bytebuddy-agent's ParameterMappingService
     */
    public void saveMapping(String className, String methodName, Map<Integer, String> mapping) {
        try {
            String key = KEY_PREFIX + className + ":" + methodName;

            // Convert Map<Integer, String> to Map<String, String> for JSON serialization
            Map<String, String> stringMap = new HashMap<>();
            for (Map.Entry<Integer, String> entry : mapping.entrySet()) {
                stringMap.put(entry.getKey().toString(), entry.getValue());
            }

            String jsonValue = objectMapper.writeValueAsString(stringMap);
            redisTemplate.opsForValue().set(key, jsonValue);

            System.out.println("[ParameterMappingService] Saved mapping: " + key + " = " + jsonValue);

        } catch (Exception e) {
            System.err.println("[ParameterMappingService] Failed to save mapping: " + e.getMessage());
            throw new RuntimeException("Failed to save parameter mapping", e);
        }
    }

    /**
     * Get parameter mapping from Redis (JSON string format)
     * Compatible with bytebuddy-agent's ParameterMappingService
     */
    public Map<Integer, String> getMapping(String className, String methodName) {
        try {
            String key = KEY_PREFIX + className + ":" + methodName;
            String jsonValue = redisTemplate.opsForValue().get(key);

            if (jsonValue == null) {
                return null;
            }

            // Convert JSON back to Map<Integer, String>
            Map<String, String> stringMap = objectMapper.readValue(jsonValue, Map.class);
            Map<Integer, String> intMap = new HashMap<>();
            for (Map.Entry<String, String> entry : stringMap.entrySet()) {
                intMap.put(Integer.parseInt(entry.getKey()), entry.getValue());
            }

            System.out.println("[ParameterMappingService] Retrieved mapping: " + key + " = " + intMap);
            return intMap;

        } catch (Exception e) {
            System.err.println("[ParameterMappingService] Failed to get mapping: " + e.getMessage());
            return null;
        }
    }

    /**
     * Delete parameter mapping from Redis
     */
    public void deleteMapping(String className, String methodName) {
        String key = KEY_PREFIX + className + ":" + methodName;
        redisTemplate.delete(key);
        System.out.println("[ParameterMappingService] Deleted mapping: " + key);
    }

    /**
     * Check if mapping exists
     */
    public boolean hasMapping(String className, String methodName) {
        String key = KEY_PREFIX + className + ":" + methodName;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
