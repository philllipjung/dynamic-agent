package com.javaagent.bytebuddy.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.commons.AgentConstants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Parameter Mapping Service - Stores parameter index to name mappings in Redis
 *
 * Key format: "paramMapping:{className}:{methodName}"
 * Value: JSON string of {"0": "userId", "1": "sessionId"}
 */
public class ParameterMappingService {

    public static JedisPool jedisPool;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY_PREFIX = "paramMapping:";

    static {
        initialize();
    }

    private static void initialize() {
        try {
            String redisHost = System.getProperty(AgentConstants.PROP_REDIS_HOST, AgentConstants.DEFAULT_REDIS_HOST);
            int redisPort = Integer.parseInt(System.getProperty(AgentConstants.PROP_REDIS_PORT, String.valueOf(AgentConstants.DEFAULT_REDIS_PORT)));

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);

            jedisPool = new JedisPool(poolConfig, redisHost, redisPort);
            System.out.println("[ParameterMappingService] Connected to Redis: " + redisHost + ":" + redisPort);
        } catch (Exception e) {
            System.err.println("[ParameterMappingService] Failed to connect to Redis: " + e.getMessage());
            jedisPool = null;
        }
    }

    /**
     * Save parameter mapping to Redis
     */
    public static void saveMapping(String className, String methodName, Map<Integer, String> mapping) {
        if (jedisPool == null) {
            System.err.println("[ParameterMappingService] Redis not available, skipping save");
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = KEY_PREFIX + className + ":" + methodName;

            // Convert Map<Integer, String> to Map<String, String> for JSON serialization
            Map<String, String> stringMap = new HashMap<>();
            for (Map.Entry<Integer, String> entry : mapping.entrySet()) {
                stringMap.put(entry.getKey().toString(), entry.getValue());
            }

            String jsonValue = objectMapper.writeValueAsString(stringMap);
            jedis.set(key, jsonValue);

            System.out.println("[ParameterMappingService] Saved mapping: " + key + " = " + jsonValue);

        } catch (Exception e) {
            System.err.println("[ParameterMappingService] Failed to save mapping: " + e.getMessage());
            throw new RuntimeException("Failed to save parameter mapping", e);
        }
    }

    /**
     * Get parameter mapping from Redis
     */
    public static Map<Integer, String> getMapping(String className, String methodName) {
        if (jedisPool == null) {
            return null;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = KEY_PREFIX + className + ":" + methodName;
            String jsonValue = jedis.get(key);

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
}
