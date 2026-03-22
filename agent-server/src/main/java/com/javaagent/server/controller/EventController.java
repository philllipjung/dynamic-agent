package com.javaagent.server.controller;

import com.javaagent.bytebuddy.redis.EventService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for Event operations
 * Provides endpoints to retrieve HTTP request/response events captured by EventAdvice
 * Events are stored in Redis via EventService
 */
@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "*")
public class EventController {

    /**
     * Get all captured events
     * GET /api/events
     *
     * Returns all events captured by EventAdvice from Redis
     */
    @GetMapping
    public Map<String, Object> getAllEvents() {
        try {
            List<Map<String, Object>> events = EventService.getAllEvents();

            return Map.of(
                "success", true,
                "events", events,
                "count", events.size(),
                "storage", "Redis",
                "message", "Events retrieved successfully"
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
     * Get a specific event by ID
     * GET /api/events/{eventId}
     *
     * Returns detailed event information for a specific event ID from Redis
     */
    @GetMapping("/{eventId}")
    public Map<String, Object> getEventById(@PathVariable String eventId) {
        try {
            Map<String, Object> event = EventService.getEvent(eventId);

            if (event != null && !event.isEmpty()) {
                return Map.of(
                    "success", true,
                    "event", event,
                    "eventId", eventId,
                    "storage", "Redis"
                );
            } else {
                return Map.of(
                    "success", false,
                    "error", "Event not found",
                    "eventId", eventId
                );
            }
        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "eventId", eventId
            );
        }
    }

    /**
     * Clear all captured events
     * DELETE /api/events
     *
     * Removes all events from Redis
     */
    @DeleteMapping
    public Map<String, Object> clearAllEvents() {
        try {
            EventService.clearAllEvents();

            return Map.of(
                "success", true,
                "message", "All events cleared successfully from Redis",
                "storage", "Redis"
            );
        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }

    /**
     * Get events formatted for UI display
     * GET /api/events/display
     *
     * Returns events in a user-friendly format with keys and values
     * Events are automatically sorted by timestamp (newest first)
     */
    @GetMapping("/display")
    public Map<String, Object> getEventsForDisplay() {
        try {
            List<Map<String, Object>> events = EventService.getAllEvents();

            // Events are already sorted by timestamp from Redis (newest first)

            return Map.of(
                "success", true,
                "events", events,
                "count", events.size(),
                "storage", "Redis",
                "message", "Events formatted for display (sorted by timestamp, newest first)"
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
     * Get event count
     * GET /api/events/count
     *
     * Returns the number of events stored in Redis
     */
    @GetMapping("/count")
    public Map<String, Object> getEventCount() {
        try {
            long count = EventService.getEventCount();

            return Map.of(
                "success", true,
                "count", count,
                "storage", "Redis"
            );
        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "count", 0
            );
        }
    }
}
