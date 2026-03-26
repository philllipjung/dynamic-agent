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

    /** Inject helper classes into target ClassLoader */
    private void injectHelper(ClassLoader targetLoader) {
        try {
            for (String helperClassName : com.javaagent.commons.AgentConstants.HELPER_CLASSES) {
                byte[] classBytes = loadHelperClassBytes(helperClassName);
                if (classBytes == null) continue;

                injectClassIntoLoader(targetLoader, helperClassName, classBytes);
                System.out.println("[ByteBuddyController] Injected helper: " + helperClassName);
            }
        } catch (Exception e) {
            System.err.println("[ByteBuddyController] Failed to inject helper: " + e.getMessage());
        }
    }

    private byte[] loadHelperClassBytes(String className) {
        try {
            String path = className.replace('.', '/') + ".class";
            java.io.InputStream is = ClassLoader.getSystemClassLoader().getResourceAsStream(path);
            if (is == null) {
                is = ByteBuddyController.class.getClassLoader().getResourceAsStream(path);
            }

            if (is == null) return null;

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            int bytesRead;
            byte[] data = new byte[4096];
            while ((bytesRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            is.close();
            return buffer.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void injectClassIntoLoader(ClassLoader targetLoader, String className, byte[] classBytes) throws Exception {
        java.lang.reflect.Method defineClass = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class
        );
        defineClass.setAccessible(true);
        defineClass.invoke(targetLoader, className, classBytes, 0, classBytes.length);
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
     * Body: {
     *   "className": "com.javaagent.server.filter.ExampleFilter",
     *   "methodName": "doFilterInternal"
     * }
     *
     * This will intercept the filter's doFilter method and capture:
     * - Request headers and body
     * - Response headers and body
     * - All events are stored and can be viewed at GET /api/events/display
     */
    @PostMapping("/createEventAdvice")
    public Map<String, Object> createEventAdvice(@RequestBody CreateEventAdviceRequest request) {
        try {
            String result = com.javaagent.bytebuddy.ByteBuddyAgent.createEventAdvice(
                request.getClassName(),
                request.getMethodName()
            );

            boolean success = result.startsWith("SUCCESS");

            return Map.of(
                "success", success,
                "message", result,
                "className", request.getClassName(),
                "methodName", request.getMethodName(),
                "viewEventsUrl", "/api/events/display",
                "clearEventsUrl", "/api/events"
            );

        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "message", "Failed to create event advice"
            );
        }
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
}
