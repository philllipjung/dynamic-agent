package com.javaagent.bytebuddy.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.commons.AgentConstants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Event Service - Stores HTTP request/response events in Redis
 *
 * Key formats:
 * - "event:{eventId}" - Individual event data
 * - "event:all" - Sorted set of all event IDs (scored by timestamp)
 */
public class EventService {

    public static JedisPool jedisPool;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String EVENT_KEY_PREFIX = "event:";
    private static final String ALL_EVENTS_KEY = "event:all";
    private static final long MAX_EVENTS = 1000;

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
            System.out.println("[EventService] Connected to Redis: " + redisHost + ":" + redisPort);
        } catch (Exception e) {
            System.err.println("[EventService] Failed to connect to Redis: " + e.getMessage());
            jedisPool = null;
        }
    }

    /**
     * Save event to Redis
     */
    public static void saveEvent(String eventId, Map<String, Object> eventData) {
        if (jedisPool == null) {
            System.err.println("[EventService] Redis not available, skipping save");
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            // Get timestamp for scoring
            Object timestampObj = eventData.get("timestamp");
            long timestamp = timestampObj instanceof Number ? ((Number) timestampObj).longValue() : System.currentTimeMillis();

            // Save individual event
            String eventKey = EVENT_KEY_PREFIX + eventId;
            String jsonValue = objectMapper.writeValueAsString(eventData);
            jedis.set(eventKey, jsonValue);

            // Add to sorted set with timestamp as score
            jedis.zadd(ALL_EVENTS_KEY, timestamp, eventId);

            // Cleanup old events
            cleanupOldEvents(jedis);

            System.out.println("[EventService] Saved event: " + eventId);

        } catch (Exception e) {
            System.err.println("[EventService] Failed to save event: " + e.getMessage());
        }
    }

    /**
     * Get all events from Redis
     */
    public static List<Map<String, Object>> getAllEvents() {
        if (jedisPool == null) {
            System.err.println("[EventService] Redis not available");
            return new ArrayList<>();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            // Get all event IDs (sorted by timestamp, newest first)
            Set<String> eventIds = jedis.zrevrange(ALL_EVENTS_KEY, 0, -1);

            List<Map<String, Object>> events = new ArrayList<>();
            for (String eventId : eventIds) {
                Map<String, Object> eventData = getEvent(eventId);
                if (eventData != null) {
                    eventData.put("eventId", eventId); // Add eventId to the map
                    events.add(eventData);
                }
            }

            return events;

        } catch (Exception e) {
            System.err.println("[EventService] Failed to get all events: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get specific event by ID
     */
    public static Map<String, Object> getEvent(String eventId) {
        if (jedisPool == null) {
            System.err.println("[EventService] Redis not available");
            return null;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String eventKey = EVENT_KEY_PREFIX + eventId;
            String jsonValue = jedis.get(eventKey);

            if (jsonValue == null) {
                return null;
            }

            return objectMapper.readValue(jsonValue, Map.class);

        } catch (Exception e) {
            System.err.println("[EventService] Failed to get event: " + e.getMessage());
            return null;
        }
    }

    /**
     * Clear all events from Redis
     */
    public static void clearAllEvents() {
        if (jedisPool == null) {
            System.err.println("[EventService] Redis not available");
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            // Get all event IDs
            Set<String> eventIds = jedis.zrange(ALL_EVENTS_KEY, 0, -1);

            // Delete individual events
            for (String eventId : eventIds) {
                String eventKey = EVENT_KEY_PREFIX + eventId;
                jedis.del(eventKey);
            }

            // Clear the sorted set
            jedis.del(ALL_EVENTS_KEY);

            System.out.println("[EventService] Cleared " + eventIds.size() + " events");

        } catch (Exception e) {
            System.err.println("[EventService] Failed to clear events: " + e.getMessage());
        }
    }

    /**
     * Cleanup old events if we exceed MAX_EVENTS
     */
    private static void cleanupOldEvents(Jedis jedis) {
        try {
            long count = jedis.zcard(ALL_EVENTS_KEY);
            if (count > MAX_EVENTS) {
                // Remove oldest events (lowest scores)
                long toRemove = count - MAX_EVENTS;
                Set<String> oldEventIds = jedis.zrange(ALL_EVENTS_KEY, 0, toRemove - 1);

                for (String eventId : oldEventIds) {
                    String eventKey = EVENT_KEY_PREFIX + eventId;
                    jedis.del(eventKey);
                    jedis.zrem(ALL_EVENTS_KEY, eventId);
                }

                System.out.println("[EventService] Cleaned up " + toRemove + " old events");
            }
        } catch (Exception e) {
            System.err.println("[EventService] Failed to cleanup old events: " + e.getMessage());
        }
    }

    /**
     * Get event count
     */
    public static long getEventCount() {
        if (jedisPool == null) {
            return 0;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zcard(ALL_EVENTS_KEY);
        } catch (Exception e) {
            System.err.println("[EventService] Failed to get event count: " + e.getMessage());
            return 0;
        }
    }
}
