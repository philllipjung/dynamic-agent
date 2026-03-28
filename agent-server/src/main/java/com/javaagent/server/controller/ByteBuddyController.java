package com.javaagent.server.controller;

import com.javaagent.bytebuddy.AttachManager;
import com.javaagent.bytebuddy.ByteBuddyAgent;
import com.javaagent.server.opensearch.OpenSearchManager;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.sun.tools.attach.VirtualMachine;

/**
 * REST API for ByteBuddy Agent operations
 * Section 1: Agent Lifecycle (Attach/Detach)
 * Section 2: OpenTelemetry Integration
 */
@RestController
@RequestMapping("/api/bytebuddy")
@CrossOrigin(origins = "*")
public class ByteBuddyController {

    // ============================================================
    // Section 1: Agent Lifecycle
    // ============================================================

    /** Attached PIDs tracking */
    private static final Set<String> attachedPids = ConcurrentHashMap.newKeySet();

    /**
     * Attach ByteBuddy Agent to a JVM
     * POST /api/bytebuddy/attach
     * Body: {"pid": "12345"} or {"className": "com.example.MyClass"}
     *
     * Dynamically attaches the ByteBuddy agent to a running JVM
     */
    @PostMapping("/attach")
    public Map<String, Object> attach(@RequestBody AttachRequest request) {
        try {
            String pid = request.getPid();

            // If className provided, find PID first
            if ((pid == null || pid.isEmpty()) && request.getClassName() != null) {
                pid = com.javaagent.arthas.ArthasManager.findPidByClassName(request.getClassName());
                if (pid.startsWith("ERROR")) {
                    return Map.of(
                        "success", false,
                        "error", "Class not found: " + request.getClassName()
                    );
                }
            }

            if (pid == null || pid.isEmpty()) {
                return Map.of(
                    "success", false,
                    "error", "Either pid or className is required"
                );
            }

            // Check if already attached
            if (attachedPids.contains(pid)) {
                return Map.of(
                    "success", true,
                    "message", "Already attached to PID " + pid,
                    "pid", pid
                );
            }

            // Find ByteBuddy Agent JAR
            String agentJar = findAgentJar();
            if (agentJar == null) {
                return Map.of(
                    "success", false,
                    "error", "ByteBuddy Agent JAR not found. Make sure bytebuddy-agent-1.0.0.jar is in the directory."
                );
            }

            // Attach agent
            VirtualMachine vm = VirtualMachine.attach(pid);
            String agentArgs = request.getInstrumentation() != null ? "instrumentation=" + request.getInstrumentation() : "";
            vm.loadAgent(agentJar, agentArgs);
            vm.detach();

            attachedPids.add(pid);

            return Map.of(
                "success", true,
                "message", "ByteBuddy Agent attached to PID " + pid,
                "pid", pid,
                "agentJar", agentJar
            );

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "message", "Failed to attach ByteBuddy Agent",
                "exceptionType", e.getClass().getName()
            );
        }
    }

    /**
     * Detach ByteBuddy Agent from a JVM
     * DELETE /api/bytebuddy/detach?pid=12345
     *
     * Note: Actual detachment happens when the target JVM terminates or agent is unloaded
     * This just removes the PID from tracking
     */
    @DeleteMapping("/detach")
    public Map<String, Object> detach(@RequestParam String pid) {
        if (attachedPids.remove(pid)) {
            return Map.of(
                "success", true,
                "message", "Detached from PID " + pid,
                "pid", pid,
                "note", "Agent will be fully detached when JVM terminates"
            );
        } else {
            return Map.of(
                "success", false,
                "error", "PID " + pid + " is not attached"
            );
        }
    }

    /**
     * Get list of attached PIDs
     * GET /api/bytebuddy/attached
     */
    @GetMapping("/attached")
    public Map<String, Object> getAttached() {
        return Map.of(
            "success", true,
            "attachedPids", attachedPids,
            "count", attachedPids.size()
        );
    }

    /**
     * Find ByteBuddy Agent JAR file
     * Looks for bytebuddy-agent-1.0.0.jar in the current directory
     */
    private String findAgentJar() {
        try {
            // Try current directory first
            File agentJar = new File("bytebuddy-agent-1.0.0.jar");
            if (agentJar.exists()) {
                return agentJar.getAbsolutePath();
            }

            // Try target directory (for development)
            agentJar = new File("bytebuddy-agent/target/bytebuddy-agent-1.0.0.jar");
            if (agentJar.exists()) {
                return agentJar.getAbsolutePath();
            }

            // Try to find relative to this class
            String classPath = ByteBuddyController.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI().getPath();
            File classFile = new File(classPath);
            if (classFile.exists()) {
                File parentDir = classFile.getParentFile();
                while (parentDir != null) {
                    agentJar = new File(parentDir, "bytebuddy-agent-1.0.0.jar");
                    if (agentJar.exists()) {
                        return agentJar.getAbsolutePath();
                    }
                    agentJar = new File(parentDir, "target/bytebuddy-agent-1.0.0.jar");
                    if (agentJar.exists()) {
                        return agentJar.getAbsolutePath();
                    }
                    parentDir = parentDir.getParentFile();
                }
            }

            return null;
        } catch (Exception e) {
            System.err.println("[ByteBuddyController] Error finding agent JAR: " + e.getMessage());
            return null;
        }
    }

    // ============================================================
    // Section 2: OpenTelemetry Integration
    // ============================================================


    /**
     * Get span names from OpenSearch
     * GET /api/bytebuddy/spanNames
     *
     * Queries otel-v1-apm-span-* index for unique span names (OpenTelemetry format)
     */
    @GetMapping("/spanNames")
    public Map<String, Object> getSpanNames() {
        try {
            List<String> spanNames = OpenSearchManager.getSpanNames();
            return Map.of(
                "success", true,
                "spanNames", spanNames,
                "count", spanNames.size()
            );
        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "spanNames", List.of(),
                "count", 0
            );
        }
    }

    /**
     * Get span attributes for a specific span name
     * GET /api/bytebuddy/spanAttributes?spanName=/FindDriverIDs
     *
     * Queries OpenSearch (jaeger-span-*) to get all attributes for this span
     * Returns arthas.* prefixed attributes (Jaeger tags format)
     */
    @GetMapping("/spanAttributes")
    public Map<String, Object> getSpanAttributes(@RequestParam(name = "spanName") String spanName) {
        try {
            List<String> attributes = OpenSearchManager.getAttributesFromSampleDocuments(spanName, 5);

            // arthas.* 속성만 필터링 (Jaeger tags format: tags.key = "arthas.attribute.userId")
            List<String> arthasAttributes = attributes.stream()
                .filter(attr -> attr.startsWith("arthas.attribute.") ||
                             attr.startsWith("arthas.") ||
                             attr.contains("arthas") ||
                             attr.contains("userId") || attr.contains("sessionId") ||
                             attr.contains("param")) // 일반적인 파라미터 이름도 포함
                .distinct()
                .collect(Collectors.toList());

            return Map.of(
                "success", true,
                "spanName", spanName,
                "attributes", arthasAttributes,
                "allAttributes", attributes,
                "count", arthasAttributes.size(),
                "index", "jaeger-span-*",
                "format", "Jaeger (tags array)"
            );
        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "spanName", spanName,
                "attributes", List.of(),
                "count", 0
            );
        }
    }

    /**
     * Create OpenTelemetry span for a method
     * POST /api/bytebuddy/createSpan
     * Body: {
     *   "pid": "12345",
     *   "className": "com.test.service.shared.UserIdService",
     *   "methodName": "setUserId"
     * }
     */
    @PostMapping("/createSpan")
    public Map<String, Object> createSpan(@RequestBody CreateSpanRequest request) {
        try {
            String pid = request.getPid();
            String className = request.getClassName();
            String methodName = request.getMethodName();

            // Direct call to ByteBuddyAgent (agent must be attached to target JVM)
            String result = com.javaagent.bytebuddy.ByteBuddyAgent.createSpan(pid, className, methodName);

            boolean success = result.startsWith("SUCCESS");
            return Map.of(
                    "success", success,
                    "message", result,
                    "type", "createSpan",
                    "className", className,
                    "methodName", methodName
            );

        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", "ERROR: Failed to create span - " + e.getMessage(),
                    "type", "createSpan",
                    "className", request.getClassName(),
                    "methodName", request.getMethodName()
            );
        }
    }

    /** Map PID to agent HTTP port */
    private static final Map<String, Integer> agentPorts = new ConcurrentHashMap<>();

    private int getAgentPort(String pid) {
        // Try to find the actual port by scanning
        return agentPorts.computeIfAbsent(pid, k -> findAgentPort());
    }

    private void setAgentPort(String pid, int port) {
        agentPorts.put(pid, port);
    }

    private int findAgentPort() {
        // Try ports from 9999 to 10010
        for (int port = 9999; port <= 10010; port++) {
            try {
                String url = "http://localhost:" + port + "/api/health";
                String response = sendGetRequest(url);
                if (response != null && response.contains("ByteBuddyAgent")) {
                    System.out.println("[ByteBuddyController] Found agent on port " + port);
                    return port;
                }
            } catch (Exception e) {
                // Port not available, try next
            }
        }
        return 9999; // default if not found
    }

    private String sendGetRequest(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(1000);
        conn.setReadTimeout(1000);

        if (conn.getResponseCode() == 200) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "utf-8"))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                return response.toString();
            }
        }
        return null;
    }

    private String sendPostRequest(String url, String jsonBody) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            return response.toString();
        }
    }

    private String extractMessage(String json) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Matcher m = p.matcher(json);
            return m.find() ? m.group(1) : json;
        } catch (Exception e) {
            return json;
        }
    }

    /** Track applied methods per PID */
    private static final Map<String, Set<String>> appliedMethodsByPid = new ConcurrentHashMap<>();

    private Set<String> getAppliedMethods(String pid) {
        return appliedMethodsByPid.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet());
    }

    private void updateAppliedMethods(String pid, Set<String> methods) {
        appliedMethodsByPid.put(pid, methods);
    }

    // ============================================================
    // Request DTOs
    // ============================================================

    public static class AttachRequest {
        private String pid;
        private String className;
        private String instrumentation; // Format: className1:methodName1:type1,className2:methodName2:type2

        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getInstrumentation() { return instrumentation; }
        public void setInstrumentation(String instrumentation) { this.instrumentation = instrumentation; }
    }

    public static class CreateSpanRequest {
        private String pid;
        private String className;
        private String methodName;

        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
    }

    /**
     * Create span attribute with Arthas-extracted parameters
     * POST /api/bytebuddy/createSpanAttribute
     * Body: {
     *   "className": "com.test.service.UserIdService",
     *   "methodName": "setUserId",
     *   "parameters": ["userId", "sessionId"]
     * }
     */
    @PostMapping("/createSpanAttribute")
    public Map<String, Object> createSpanAttribute(@RequestBody CreateSpanAttributeRequest request) {
        try {
            String pid = request.getPid();
            String className = request.getClassName();
            String methodName = request.getMethodName();

            // 1. 파라미터 매핑 생성
            java.util.Map<Integer, String> paramMapping = new java.util.LinkedHashMap<>();
            if (request.getParameters() != null) {
                int index = 0;
                for (String paramName : request.getParameters()) {
                    paramMapping.put(index++, paramName);
                }
            }

            // 2. Direct call to ByteBuddyAgent (agent must be attached to target JVM)
            String result = com.javaagent.bytebuddy.ByteBuddyAgent.createSpanAttributeAdvice(
                pid,
                className,
                methodName,
                paramMapping
            );

            boolean success = result.startsWith("SUCCESS");
            return Map.of(
                "success", success,
                "message", result,
                "type", "createSpanAttribute",
                "className", className,
                "methodName", methodName,
                "parameters", request.getParameters(),
                "paramMapping", paramMapping,
                "attributePrefix", "arthas.attribute."
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "message", "ERROR: Failed to create span attribute - " + e.getMessage(),
                "type", "createSpanAttribute",
                "className", request.getClassName(),
                "methodName", request.getMethodName()
            );
        }
    }

    /**
     * Request DTO for createSpanAttribute
     */
    public static class CreateSpanAttributeRequest {
        private String pid;
        private String className;
        private String methodName;
        private String[] parameters;

        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public String[] getParameters() { return parameters; }
        public void setParameters(String[] parameters) { this.parameters = parameters; }
    }

    // ============================================================
    // Section 3: Event Capture (Spring Filter)
    // ============================================================

    /**
     * Create Event Advice for Spring Filter to capture HTTP request/response
     * POST /api/bytebuddy/createEventAdvice
     *
     * Note: This endpoint is kept for backward compatibility.
     * It internally delegates to createKernelAdvice which provides HTTP capture functionality.
     */
    @PostMapping("/createEventAdvice")
    public Map<String, Object> createEventAdvice(@RequestBody CreateEventAdviceRequest request) {
        return createKernelAdvice(new CreateKernelAdviceRequest() {{
            setClassName(request.getClassName());
            setMethodName(request.getMethodName());
        }});
    }

    /**
     * Request DTO for createEventAdvice
     */
    public static class CreateEventAdviceRequest {
        private String className;
        private String methodName;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
    }

    // ============================================================
    // Section 4: Kernel Tracing
    // ============================================================

    /**
     * Instrument all Spring Filters to capture HTTP request headers and body
     * POST /api/bytebuddy/instrumentFilters
     *
     * This will automatically find and instrument all javax.servlet.Filter implementations
     * to print HTTP request headers and body using KernelAdvice
     */
    @PostMapping("/instrumentFilters")
    public Map<String, Object> instrumentFilters() {
        try {
            // Find agent HTTP server port for first attached PID
            if (attachedPids.isEmpty()) {
                return Map.of(
                    "success", false,
                    "error", "No attached PIDs. Please attach to a JVM first."
                );
            }

            String pid = attachedPids.iterator().next();
            int agentPort = getAgentPort(pid);

            // Call agent's HTTP server
            String url = "http://localhost:" + agentPort + "/api/instrumentFilters";
            String response = sendPostRequest(url, "{}");

            return Map.of(
                "success", true,
                "message", "Filter instrumentation request sent to PID " + pid,
                "agentResponse", response,
                "features", List.of(
                    "Print HTTP request headers",
                    "Print HTTP request body",
                    "Works with all Spring Filters",
                    "No code changes required"
                )
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "message", "Failed to instrument filters"
            );
        }
    }

    /**
     * Create Kernel Advice for kernel-level tracing
     * POST /api/bytebuddy/createKernelAdvice
     * Body: {
     *   "className": "com.example.demo.Service1Controller",
     *   "methodName": "index"
     * }
     *
     * This will enable automatic kernel-level tracing:
     * - Automatic span creation with trace/span ID extraction
     * - Thread name customization (reactor-http-epoll, parallel, boundedElastic)
     * - Child span support for parallel/async operations
     * - Automatic span completion on method exit
     *
     * Example: Apply to Service1Controller.index to automatically:
     * 1. Create span on method entry
     * 2. Customize thread names with trace/span info
     * 3. Track parallel and boundedElastic task threads
     * 4. Complete span on method exit
     */
    @PostMapping("/createKernelAdvice")
    public Map<String, Object> createKernelAdvice(@RequestBody CreateKernelAdviceRequest request) {
        try {
            String result = com.javaagent.bytebuddy.ByteBuddyAgent.createKernelAdvice(
                request.getClassName(),
                request.getMethodName()
            );

            boolean success = result.startsWith("SUCCESS");

            return Map.of(
                "success", success,
                "message", result,
                "className", request.getClassName(),
                "methodName", request.getMethodName(),
                "features", List.of(
                    "Automatic span creation",
                    "Thread name customization with trace/span info",
                    "Reactor scheduler integration (parallel, boundedElastic)",
                    "Child span support for parallel operations",
                    "Automatic span completion on method exit"
                )
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "message", "Failed to create kernel advice"
            );
        }
    }

    /**
     * Request DTO for createKernelAdvice
     */
    public static class CreateKernelAdviceRequest {
        private String className;
        private String methodName;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
    }

    // ============================================================
    // Section 5: Thread Name Verification (/proc)
    // ============================================================

    /**
     * Get thread information from /proc filesystem
     * GET /api/bytebuddy/threadInfo
     *
     * Returns information about all active threads including:
     * - Java thread name (Thread.getName())
     * - Native thread name from /proc/<pid>/task/<tid>/comm (Linux)
     * - Thread ID (TID)
     * - Thread state
     *
     * This verifies that Thread.setName() actually updates the kernel thread name
     */
    @GetMapping("/threadInfo")
    public Map<String, Object> getThreadInfo() {
        try {
            // Get current process PID
            String processPid = getProcessPid();
            String osName = System.getProperty("os.name").toLowerCase();

            // Get all active threads
            Set<Thread> threads = Thread.getAllStackTraces().keySet();

            java.util.List<Map<String, Object>> threadInfoList = new java.util.ArrayList<>();

            for (Thread thread : threads) {
                Map<String, Object> info = new java.util.HashMap<>();
                long threadId = thread.getId();
                String javaThreadName = thread.getName();
                String threadState = thread.getState().toString();
                String nativeThreadName = null;
                String commFilePath = null;

                // On Linux, read from /proc/<pid>/task/<tid>/comm
                if (osName.contains("linux")) {
                    commFilePath = "/proc/" + processPid + "/task/" + threadId + "/comm";
                    nativeThreadName = readNativeThreadName(commFilePath);
                } else if (osName.contains("windows")) {
                    // On Windows, /proc doesn't exist
                    nativeThreadName = "N/A (Windows)";
                    commFilePath = "N/A (Windows)";
                } else if (osName.contains("mac")) {
                    // macOS doesn't have /proc either
                    nativeThreadName = "N/A (macOS)";
                    commFilePath = "N/A (macOS)";
                }

                info.put("threadId", threadId);
                info.put("javaThreadName", javaThreadName);
                info.put("nativeThreadName", nativeThreadName);
                info.put("commFilePath", commFilePath);
                info.put("threadState", threadState);
                info.put("isRenamed", isThreadNameCustom(javaThreadName));
                info.put("match", javaThreadName.equals(nativeThreadName));

                threadInfoList.add(info);
            }

            return Map.of(
                "success", true,
                "processPid", processPid,
                "os", osName,
                "totalThreads", threads.size(),
                "threads", threadInfoList,
                "note", "On Linux, nativeThreadName is read from /proc/<pid>/task/<tid>/comm"
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "message", "Failed to get thread info"
            );
        }
    }

    /**
     * Get current process PID
     */
    private String getProcessPid() {
        try {
            // Try to get PID from runtime bean (works on most JVMs)
            String jvmName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            if (jvmName != null && jvmName.contains("@")) {
                return jvmName.split("@")[0];
            }

            // Fallback: environment variable
            String pid = System.getenv().get("PID");
            if (pid != null) {
                return pid;
            }

            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Read native thread name from /proc/<pid>/task/<tid>/comm
     */
    private String readNativeThreadName(String commFilePath) {
        try {
            java.io.File commFile = new java.io.File(commFilePath);
            if (!commFile.exists()) {
                return "FILE_NOT_FOUND";
            }

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(commFile))) {
                String name = reader.readLine();
                return name != null ? name.trim() : "";
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Check if thread name has been customized (not a default pattern)
     */
    private boolean isThreadNameCustom(String threadName) {
        // Default thread patterns
        if (threadName == null) return false;

        String[] defaultPatterns = {
            "reactor-http-epoll-",
            "reactor-http-nio-",
            "parallel-",
            "boundedElastic-",
            "scheduling-",
            "ForkJoinPool.",
            "main",
            "Reference Handler",
            "Finalizer",
            "Signal Dispatcher",
            "Common-Cleaner"
        };

        for (String pattern : defaultPatterns) {
            if (threadName.startsWith(pattern)) {
                return false;
            }
        }

        // If it looks like our custom format (e.g., "3a5b7c12d456ABCTSRV12345")
        if (threadName.matches("^[a-f0-9]{13}[A-Z]{3}\\d{4}$")) {
            return true;
        }

        return false;
    }
}
