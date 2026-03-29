package com.javaagent.server.controller;

import com.javaagent.server.dto.InstrumentationConflictReport;
import com.javaagent.server.service.InstrumentationConflictDetectorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for instrumentation conflict detection
 *
 * Core APIs:
 * - Check conflicts before instrumenting a specific method
 * - Generate full conflict report for a PID
 */
@RestController
@RequestMapping("/api/conflicts")
@CrossOrigin(origins = "*")
public class InstrumentationConflictController {

    @Autowired
    private InstrumentationConflictDetectorService conflictDetector;

    /**
     * Check if a specific instrumentation would conflict
     *
     * GET /api/conflicts/check?pid=11248&className=com.example.OrderController&methodName=createOrder
     */
    @GetMapping("/check")
    public Map<String, Object> checkConflict(
            @RequestParam String pid,
            @RequestParam String className,
            @RequestParam String methodName) {
        try {
            Map<String, Object> result = conflictDetector.checkInstrumentationConflict(pid, className, methodName);

            return Map.of(
                "success", true,
                "check", result
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }

    /**
     * Generate a full conflict report for a PID
     *
     * GET /api/conflicts/report?pid=11248
     */
    @GetMapping("/report")
    public Map<String, Object> getConflictReport(@RequestParam String pid) {
        try {
            InstrumentationConflictReport report = conflictDetector.generateConflictReport(pid);

            return Map.of(
                "success", true,
                "report", report
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }
}
