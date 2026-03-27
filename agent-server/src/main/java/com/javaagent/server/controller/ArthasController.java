package com.javaagent.server.controller;

import com.javaagent.arthas.ArthasManager;
import com.javaagent.server.service.ParameterMappingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for Arthas Agent operations
 * Section 1: Trace, Stack, Watch (with params and return values)
 */
@RestController
@RequestMapping("/api/arthas")
@CrossOrigin(origins = "*")
public class ArthasController {

    @Autowired
    private ParameterMappingService parameterMappingService;

    /**
     * Attach Arthas to a JVM by class name or PID
     * POST /api/arthas/attach
     * Body: {"className": "com.example.MyClass"} or {"pid": "12345"}
     */
    @PostMapping("/attach")
    public Map<String, Object> attach(@RequestBody AttachRequest request) {
        String result;
        if (request.getPid() != null && !request.getPid().isEmpty()) {
            result = ArthasManager.attachByPid(request.getPid());
        } else if (request.getClassName() != null && !request.getClassName().isEmpty()) {
            result = ArthasManager.attachByClassName(request.getClassName());
        } else {
            result = "ERROR: Either className or pid is required";
        }
        return Map.of(
                "success", result.startsWith("SUCCESS"),
                "message", result,
                "className", request.getClassName() != null ? request.getClassName() : "",
                "pid", request.getPid() != null ? request.getPid() : ""
        );
    }

    /**
     * Execute trace command
     * POST /api/arthas/trace
     * Body: {"className": "com.example.MyClass", "methodName": "myMethod"}
     */
    @PostMapping("/trace")
    public Map<String, Object> trace(@RequestBody MethodRequest request) {
        String result = ArthasManager.trace(request.getClassName(), request.getMethodName());
        return Map.of(
                "success", !result.startsWith("ERROR"),
                "message", result,
                "command", "trace " + request.getClassName() + " " + request.getMethodName()
        );
    }

    /**
     * Execute stack command
     * POST /api/arthas/stack
     * Body: {"className": "com.example.MyClass", "methodName": "myMethod"}
     */
    @PostMapping("/stack")
    public Map<String, Object> stack(@RequestBody MethodRequest request) {
        String result = ArthasManager.stack(request.getClassName(), request.getMethodName());
        return Map.of(
                "success", !result.startsWith("ERROR"),
                "message", result,
                "command", "stack " + request.getClassName() + " " + request.getMethodName()
        );
    }

    /**
     * Execute watch command (with parameters and return values) via HTTP API
     * POST /api/arthas/watch
     * Body: {"className": "com.example.MyClass", "methodName": "myMethod", "expression": "{params, returnObj}", "limit": 5}
     */
    @PostMapping("/watch")
    public Map<String, Object> watch(@RequestBody WatchRequest request) {
        try {
            // Use HTTP API for watch command (returns JSON)
            org.json.JSONObject result = ArthasManager.watchWithParameters(
                request.getClassName(),
                request.getMethodName(),
                request.getLimit()
            );

            if (result.has("error")) {
                return Map.of(
                    "success", false,
                    "error", result.getString("error"),
                    "command", "watch " + request.getClassName() + " " + request.getMethodName()
                );
            }

            return Map.of(
                "success", true,
                "data", result.toMap(),
                "command", "watch " + request.getClassName() + " " + request.getMethodName() + " -n " + request.getLimit()
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "command", "watch " + request.getClassName() + " " + request.getMethodName()
            );
        }
    }

    /**
     * Get parameter history from multiple method calls
     * GET /api/arthas/getParameter?className=com.example.MyClass&methodName=setUserId&limit=5
     *
     * Returns all captured parameter values from multiple method calls
     */
    @GetMapping("/getParameter")
    public Map<String, Object> getParameter(@RequestParam("className") String className,
                                             @RequestParam("methodName") String methodName,
                                             @RequestParam(value = "limit", defaultValue = "5") int limit) {
        try {
            // Get parameter history from Arthas watch
            org.json.JSONObject result = ArthasManager.watchWithParameters(className, methodName, limit);

            if (result.has("error")) {
                return Map.of(
                    "success", false,
                    "error", result.getString("error"),
                    "className", className,
                    "methodName", methodName
                );
            }

            // Parse parameter history
            java.util.List<java.util.Map<String, Object>> calls = new java.util.ArrayList<>();
            if (result.has("parameters")) {
                org.json.JSONArray paramsArray = result.getJSONArray("parameters");
                for (int i = 0; i < paramsArray.length(); i++) {
                    java.util.Map<String, Object> callInfo = new java.util.LinkedHashMap<>();
                    callInfo.put("callNumber", i + 1);
                    callInfo.put("parameter", paramsArray.get(i));
                    calls.add(callInfo);
                }
            }

            return Map.of(
                "success", true,
                "className", className,
                "methodName", methodName,
                "totalCalls", calls.size(),
                "calls", calls,
                "message", "Found " + calls.size() + " method call(s)"
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "className", className,
                "methodName", methodName
            );
        }
    }

    /**
     * Start async watch job
     * POST /api/arthas/startWatch
     * Body: {"className": "com.example.MyClass", "methodName": "myMethod", "limit": 1}
     */
    @PostMapping("/startWatch")
    public Map<String, Object> startWatch(@RequestBody WatchRequest request) {
        try {
            String jobId = ArthasManager.startWatchAsync(
                request.getClassName(),
                request.getMethodName(),
                request.getLimit()
            );

            return Map.of(
                "success", true,
                "jobId", jobId,
                "message", "Watch job started. Call the method, then check result with /api/arthas/getWatchResult/" + jobId,
                "status", "RUNNING"
            );

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }

    /**
     * Get watch result
     * GET /api/arthas/getWatchResult/{jobId}
     */
    @GetMapping("/getWatchResult/{jobId}")
    public Map<String, Object> getWatchResult(@PathVariable("jobId") String jobId) {
        try {
            org.json.JSONObject result = ArthasManager.getWatchResult(jobId);

            // Convert JSONObject to Map
            Map<String, Object> resultMap = new java.util.LinkedHashMap<>();
            for (String key : result.keySet()) {
                Object value = result.get(key);
                if (value instanceof org.json.JSONObject) {
                    // Keep as JSONObject for now, Spring will convert
                    resultMap.put(key, value.toString());
                } else if (value instanceof org.json.JSONArray) {
                    resultMap.put(key, value.toString());
                } else {
                    resultMap.put(key, value);
                }
            }

            return resultMap;

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "jobId", jobId
            );
        }
    }

    /**
     * Search for classes (sc command)
     * GET /api/arthas/sc?pattern=com.example
     */
    @GetMapping("/sc")
    public Map<String, Object> searchClass(@RequestParam("pattern") String pattern) {
        String result = ArthasManager.searchClass(pattern);
        return Map.of(
                "success", !result.startsWith("ERROR"),
                "message", result,
                "pattern", pattern
        );
    }

    /**
     * Search for methods (sm command)
     * GET /api/arthas/sm?className=com.example.MyClass&methodPattern=get*
     */
    @GetMapping("/sm")
    public Map<String, Object> searchMethod(@RequestParam("className") String className,
                                            @RequestParam(value = "methodPattern", required = false, defaultValue = "*") String methodPattern) {
        String result = ArthasManager.searchMethod(className, methodPattern);
        return Map.of(
                "success", !result.startsWith("ERROR"),
                "message", result,
                "className", className,
                "methodPattern", methodPattern
        );
    }

    /**
     * Get classloader information
     * GET /api/arthas/classloader?className=com.example.MyClass
     */
    @GetMapping("/classloader")
    public Map<String, Object> getClassLoader(@RequestParam("className") String className) {
        String result = ArthasManager.getClassLoaderInfo(className);
        return Map.of(
                "success", !result.startsWith("ERROR"),
                "message", result
        );
    }

    /**
     * Detach Arthas
     * DELETE /api/arthas/detach?pid=12345
     */
    @DeleteMapping("/detach")
    public Map<String, Object> detach(@RequestParam("pid") String pid) {
        String result = ArthasManager.detach(pid);
        return Map.of(
                "success", result.startsWith("SUCCESS"),
                "message", result
        );
    }

    /**
     * Reset all Arthas sessions
     * POST /api/arthas/reset
     */
    @PostMapping("/reset")
    public Map<String, Object> resetAll() {
        String result = ArthasManager.resetAllSessions();
        return Map.of(
                "success", true,
                "message", result
        );
    }

    /**
     * Get active sessions
     * GET /api/arthas/sessions
     */
    @GetMapping("/sessions")
    public Map<String, Object> getSessions() {
        return Map.of(
                "sessions", ArthasManager.getActiveSessions(),
                "count", ArthasManager.getActiveSessions().size()
        );
    }

    /**
     * Get all Java processes
     * GET /api/arthas/processes
     */
    @GetMapping("/processes")
    public Map<String, Object> getProcesses() {
        try {
            java.util.List<Map<String, String>> processes = ArthasManager.listJavaProcesses();
            java.util.Set<String> attachedPids = ArthasManager.getActiveSessions().keySet();
            return Map.of(
                    "processes", processes,
                    "attachedPids", attachedPids
            );
        } catch (Exception e) {
            return Map.of(
                    "processes", java.util.List.of(),
                    "attachedPids", java.util.Set.of(),
                    "error", e.getMessage()
            );
        }
    }

    /**
     * Find PID by class name
     * GET /api/arthas/findPid?className=com.example.MyClass
     */
    @GetMapping("/findPid")
    public Map<String, Object> findPid(@RequestParam("className") String className) {
        String result = ArthasManager.findPidByClassName(className);
        return Map.of(
                "success", !result.startsWith("ERROR"),
                "pid", result.startsWith("ERROR") ? null : result,
                "message", result
        );
    }

    /**
     * Map parameter indices to names
     * POST /api/arthas/mapParameters
     * Body: {
     *   "className": "com.example.MyClass",
     *   "methodName": "setUserId",
     *   "mapping": {"0": "userId", "1": "sessionId"}
     * }
     *
     * Saves the parameter mapping to Redis and SpanAttributeAdvice cache
     */
    @PostMapping("/mapParameters")
    public Map<String, Object> mapParameters(@RequestBody MapParameterRequest request) {
        try {
            // Convert mapping to proper format
            java.util.Map<Integer, String> paramMapping = new java.util.LinkedHashMap<>();
            if (request.getMapping() != null) {
                for (java.util.Map.Entry<String, String> entry : request.getMapping().entrySet()) {
                    try {
                        int index = Integer.parseInt(entry.getKey());
                        paramMapping.put(index, entry.getValue());
                    } catch (NumberFormatException e) {
                        return Map.of(
                            "success", false,
                            "error", "Invalid parameter index: " + entry.getKey()
                        );
                    }
                }
            }

            // Store mapping in Redis (agent-server)
            parameterMappingService.saveMapping(request.getClassName(), request.getMethodName(), paramMapping);

            // Also store in SpanAdvice cache for runtime use
            com.javaagent.bytebuddy.advices.SpanAdvice.setParameterMapping(
                request.getClassName(),
                request.getMethodName(),
                paramMapping
            );

            return Map.of(
                "success", true,
                "message", "매핑 저장 완료 (Redis + Advice Cache)",
                "className", request.getClassName(),
                "methodName", request.getMethodName(),
                "mapping", paramMapping,
                "storage", "Redis + SpanAttributeAdvice"
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }

    // Request DTOs
    public static class AttachRequest {
        private String className;
        private String pid;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }
    }

    public static class MethodRequest {
        private String className;
        private String methodName;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
    }

    public static class WatchRequest extends MethodRequest {
        private String expression = "{params, returnObj}";
        private int limit = 5;

        public String getExpression() { return expression; }
        public void setExpression(String expression) { this.expression = expression; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
    }

    public static class MapParameterRequest {
        private String className;
        private String methodName;
        private java.util.Map<String, String> mapping;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public java.util.Map<String, String> getMapping() { return mapping; }
        public void setMapping(java.util.Map<String, String> mapping) { this.mapping = mapping; }
    }
}
