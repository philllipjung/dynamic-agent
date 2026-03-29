package com.javaagent.arthas;

import com.javaagent.commons.AgentConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Arthas Manager using Tunnel Server HTTP API
 * Supports: trace, stack, watch, classloader discovery (sc, sm)
 *
 * Configuration:
 * - Loaded from AgentConfiguration (application.properties)
 * - Required parameters: arthas.tunnel.server.host, arthas.tunnel.server.port
 * - Call configureTunnelServer() before using if AgentConfiguration is not available
 */
public class ArthasManager {

    private static final Map<String, ArthasSession> activeSessions = new ConcurrentHashMap<>();
    private static final Map<String, String> pidToSessionId = new ConcurrentHashMap<>();
    private static String arthasHome;
    private static String tunnelServerHost;
    private static int tunnelServerPort;

    // Async watch support
    private static final ExecutorService watchExecutor = Executors.newCachedThreadPool();
    private static final Map<String, JSONObject> watchResults = new ConcurrentHashMap<>();
    private static final Map<String, String> watchStatus = new ConcurrentHashMap<>(); // RUNNING, COMPLETED, FAILED

    // Async stack support
    private static final ExecutorService stackExecutor = Executors.newCachedThreadPool();
    private static final Map<String, String> stackResults = new ConcurrentHashMap<>();
    private static final Map<String, String> stackStatus = new ConcurrentHashMap<>(); // RUNNING, COMPLETED, FAILED

    // Async trace support
    private static final ExecutorService traceExecutor = Executors.newCachedThreadPool();
    private static final Map<String, String> traceResults = new ConcurrentHashMap<>();
    private static final Map<String, String> traceStatus = new ConcurrentHashMap<>(); // RUNNING, COMPLETED, FAILED

    static {
        tunnelServerHost = System.getProperty(AgentConstants.PROP_ARTHAS_HOST, AgentConstants.DEFAULT_ARTHAS_HOST);
        tunnelServerPort = Integer.parseInt(System.getProperty(AgentConstants.PROP_ARTHAS_PORT, String.valueOf(AgentConstants.DEFAULT_ARTHAS_PORT)));
    }

    /**
     * Configure tunnel server host and port
     */
    public static void configureTunnelServer(String host, int port) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("arthas.tunnel.server.host must be configured");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("arthas.tunnel.server.port must be between 1 and 65535");
        }
        tunnelServerHost = host;
        tunnelServerPort = port;
        System.out.println("[ArthasManager] Tunnel server configured: " + host + ":" + port);
    }

    /**
     * Get tunnel server host
     */
    public static String getTunnelServerHost() {
        return tunnelServerHost;
    }

    /**
     * Get tunnel server port
     */
    public static int getTunnelServerPort() {
        return tunnelServerPort;
    }

    /**
     * Attach Arthas to a JVM by class name (without PID)
     */
    public static String attachByClassName(String className) {
        try {
            String pid = findPidByClassName(className);
            if (pid.startsWith("ERROR")) {
                return pid;
            }
            return attachByPid(pid);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Attach Arthas to a JVM by PID using HTTP API
     * Runs arthas-boot.jar to attach, which starts the HTTP API server on port 8563
     */
    public static String attachByPid(String pid) {
        if (activeSessions.containsKey(pid)) {
            return "Arthas already attached to PID: " + pid;
        }

        try {
            // Download Arthas if not present
            ensureArthasAvailable();

            File arthasBoot = new File(arthasHome, "arthas-boot.jar");
            if (!arthasBoot.exists()) {
                return "ERROR: Arthas boot jar not found: " + arthasBoot.getAbsolutePath();
            }

            System.out.println("[ArthasManager] Attaching Arthas to PID " + pid + " (HTTP API will be on port " + tunnelServerPort + ")");

            // Start Arthas in background with HTTP API enabled
            // Arthas will attach to the target PID and start HTTP API server on configured port
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows: Use cmd.exe
                // Note: --http-port, not --target-port. PID must come after options.
                pb = new ProcessBuilder("cmd.exe", "/c",
                    "start /B java -jar \"" + arthasBoot.getAbsolutePath() + "\" --target-ip " + tunnelServerHost + " --http-port " + tunnelServerPort + " " + pid);
            } else {
                // Unix/Mac: Use bash
                pb = new ProcessBuilder("bash", "-c",
                    "java -jar " + arthasBoot.getAbsolutePath() + " --target-ip " + tunnelServerHost + " --http-port " + tunnelServerPort + " " + pid + " &");
            }

            pb.redirectErrorStream(true);
            pb.start();

            // Wait a moment for Arthas to start and HTTP API to become available
            Thread.sleep(3000);

            // Create session
            String sessionId = "arthas-" + pid + "-" + System.currentTimeMillis();
            ArthasSession session = new ArthasSession(sessionId, pid);
            session.start();

            activeSessions.put(pid, session);
            pidToSessionId.put(pid, sessionId);

            return "SUCCESS: Arthas attached to PID " + pid + " (Session: " + sessionId + "), HTTP API on port " + tunnelServerPort;
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: Failed to attach Arthas - " + e.getMessage();
        }
    }

    /**
     * Execute command via Arthas HTTP API (JSON)
     * API URL: http://{host}:{port}/api
     * Request format: {"action": "exec", "command": "watch ...", "execTimeout": "30000"}
     * Response format: {"state": "SUCCEEDED", "body": {"results": [...]}}
     */
    public static String executeTunnelApiCommand(String pid, String command) throws IOException {
        HttpURLConnection conn = null;
        try {
            // Build API URL
            String urlString = String.format("http://%s:%d/api", tunnelServerHost, tunnelServerPort);
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(60000);

            // Build JSON request
            JSONObject requestBody = new JSONObject();
            requestBody.put("action", "exec");
            requestBody.put("command", command);
            requestBody.put("execTimeout", "60000");

            String requestString = requestBody.toString();
            System.out.println("[ArthasManager] API Request: " + requestString);

            // Send request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Read response
            int responseCode = conn.getResponseCode();
            System.out.println("[ArthasManager] Response code: " + responseCode);

            BufferedReader br;
            if (responseCode >= 200 && responseCode < 300) {
                br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            String responseBody = response.toString();
            System.out.println("[ArthasManager] Response length: " + responseBody.length());

            return responseBody;

        } catch (Exception e) {
            System.err.println("[ArthasManager] API call failed: " + e.getMessage());
            e.printStackTrace();
            return "{\"error\": \"API call failed: " + e.getMessage().replace("\"", "'") + "\"}";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Execute watch command via HTTP API (returns JSON with parameters)
     * Response format: {"state": "SUCCEEDED", "body": {"results": [{"type": "watch", "value": "..."}]}}
     *
     * Arthas watch value format examples:
     * - "params[0]=\\"userId\\", params[1]=\\"12345\\""
     * - "@ArrayList[]@ArrayList[@String[]..."
     */
    public static JSONObject watchWithParameters(String className, String methodName, int limit) {
        String result = null;  // Declare outside try block for error handling
        try {
            String pid = getFirstAvailablePid();
            if (pid.startsWith("ERROR") || pid.isEmpty()) {
                return new JSONObject().put("error", "No running JVM found");
            }

            // Build watch command to capture parameter values only
            // User will provide parameter names in UI
            String watchCmd = String.format("watch %s %s params -n %d -x 1",
                className, methodName, limit);

            System.out.println("[ArthasManager] Executing watch: " + watchCmd);

            // Execute via HTTP API
            result = executeTunnelApiCommand(pid, watchCmd);

            // Debug: Print raw response to file
            System.err.println("[ArthasManager] Raw response length: " + (result != null ? result.length() : 0));
            System.err.println("[ArthasManager] Raw response (first 500 chars): " + (result != null ? result.substring(0, Math.min(500, result.length())) : "null"));
            System.err.println("[ArthasManager] Raw response starts with: " + (result != null && result.length() > 0 ? "[" + result.charAt(0) + "]" : "null"));
            System.err.println("[ArthasManager] Raw response ends with: " + (result != null && result.length() > 0 ? "[" + result.charAt(result.length() - 1) + "]" : "null"));

            // Handle nested JSON string response (Arthas API returns escaped JSON)
            String jsonStr = result;
            if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                // Remove quotes and unescape
                jsonStr = result.substring(1, result.length() - 1)
                                 .replace("\\\"", "\"")
                                 .replace("\\\\", "\\");
                System.err.println("[ArthasManager] Unescaped JSON: " + jsonStr);
            }

            // Parse JSON response
            System.err.println("[ArthasManager] Attempting to parse: " + (jsonStr != null ? jsonStr.substring(0, Math.min(100, jsonStr.length())) : "null"));
            JSONObject response = new JSONObject(jsonStr);

            // Check for errors
            if (response.has("error")) {
                return response;
            }

            // Check response state
            String state = response.optString("state", "UNKNOWN");
            // Allow both SUCCEEDED and INTERRUPTED (timeout waiting for method call)
            if (!"SUCCEEDED".equals(state) && !"INTERRUPTED".equals(state)) {
                return new JSONObject().put("error", "Command execution failed: " + state);
            }

            // If INTERRUPTED, return empty parameters (method not called)
            if ("INTERRUPTED".equals(state)) {
                JSONObject resultJson = new JSONObject();
                resultJson.put("success", true);
                resultJson.put("warning", "Method not called within timeout period");
                resultJson.put("className", className);
                resultJson.put("methodName", methodName);
                resultJson.put("parameters", new JSONArray());
                resultJson.put("parameterCount", 0);
                return resultJson;
            }

            // Parse parameters from response body
            JSONArray parameters = new JSONArray();
            if (response.has("body")) {
                JSONObject body = response.getJSONObject("body");
                if (body.has("results")) {
                    JSONArray results = body.getJSONArray("results");

                    // Extract parameter information from watch results
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject resultItem = results.getJSONObject(i);

                        // Watch results have type "watch" and a value field
                        if ("watch".equals(resultItem.optString("type"))) {
                            String value = resultItem.optString("value", "");

                            System.out.println("[ArthasManager] Watch value: " + value);

                            // Parse Arthas watch output format
                            // Example: "params[0]=\\"userId\\", params[1]=\\"sessionId\\""
                            parameters = parseWatchValue(value);
                        }
                    }
                }
            }

            // If no parameters found, return empty array (no hardcoded defaults)
            if (parameters.length() == 0) {
                System.out.println("[ArthasManager] No parameters extracted from watch output");
                System.out.println("[ArthasManager] Watch value was: " + (response.has("body") ? response.getJSONObject("body").toString() : "empty"));
            }

            JSONObject resultJson = new JSONObject();
            resultJson.put("success", true);
            resultJson.put("className", className);
            resultJson.put("methodName", methodName);
            resultJson.put("parameters", parameters);
            resultJson.put("parameterCount", parameters.length());
            resultJson.put("rawResponse", response);

            System.out.println("[ArthasManager] Extracted " + parameters.length() + " parameters");

            return resultJson;

        } catch (org.json.JSONException e) {
            System.err.println("[ArthasManager] JSON parse error: " + e.getMessage());
            System.err.println("[ArthasManager] Raw response: " + (result != null ? result.substring(0, Math.min(200, result.length())) : "null"));
            e.printStackTrace();
            return new JSONObject().put("error", "JSON parse error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return new JSONObject().put("error", e.getMessage());
        }
    }

    /**
     * Parse Arthas watch value to extract parameters
     * Handles various Arthas output formats
     */
    private static JSONArray parseWatchValue(String value) {
        JSONArray parameters = new JSONArray();

        if (value == null || value.isEmpty()) {
            return parameters;
        }

        try {
            // Remove quotes and escape characters
            value = value.replace("\\\"", "\"");
            value = value.replace("\\\\", "\\");

            // Try to parse params[N]=pattern
            // Example: params[0]="userId", params[1]="sessionId"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("params\\[(\\d+)\\]\\s*=\\s*\"?([^\"]+)\"?");
            java.util.regex.Matcher matcher = pattern.matcher(value);

            java.util.Set<Integer> foundIndices = new java.util.HashSet<>();

            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                String paramName = matcher.group(2).trim();

                JSONObject paramInfo = new JSONObject();
                paramInfo.put("index", index);
                paramInfo.put("name", paramName);
                paramInfo.put("type", "String");
                paramInfo.put("value", paramName);
                paramInfo.put("extracted", true);

                // Ensure parameters array is large enough
                while (parameters.length() <= index) {
                    parameters.put(new JSONObject());
                }
                parameters.put(index, paramInfo);
                foundIndices.add(index);
            }

            // If no params[N] pattern found, try alternative parsing
            if (foundIndices.isEmpty()) {
                // Split by comma and create parameter entries
                String[] parts = value.split("[,\\[\\]]");
                int idx = 0;
                for (String part : parts) {
                    part = part.trim();
                    if (!part.isEmpty() && !part.contains("params")) {
                        JSONObject paramInfo = new JSONObject();
                        paramInfo.put("index", idx);
                        paramInfo.put("name", "param" + idx);
                        paramInfo.put("type", "Object");
                        paramInfo.put("value", part);
                        parameters.put(paramInfo);
                        idx++;
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[ArthasManager] Failed to parse watch value: " + e.getMessage());
        }

        return parameters;
    }

    /**
     * Execute trace command via HTTP API
     */
    public static String trace(String className, String methodName) {
        try {
            String pid = getFirstAvailablePid();
            if (pid.startsWith("ERROR") || pid.isEmpty()) {
                return "ERROR: No running JVM found. Please start a Java application first.";
            }

            // Build trace command with -n 1 to limit invocations and avoid timeout
            String traceCmd = "trace " + className + " " + methodName + " -n 1";

            // Execute via HTTP API
            return executeTunnelApiCommand(pid, traceCmd);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Execute stack command via HTTP API
     */
    public static String stack(String className, String methodName) {
        try {
            String pid = getFirstAvailablePid();
            if (pid.startsWith("ERROR") || pid.isEmpty()) {
                return "ERROR: No running JVM found. Please start a Java application first.";
            }

            // Build stack command with -n 1 to limit invocations and avoid timeout
            String stackCmd = "stack " + className + " " + methodName + " -n 1";

            // Execute via HTTP API
            return executeTunnelApiCommand(pid, stackCmd);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Execute watch command (legacy, for backward compatibility)
     * Use watchWithParameters instead for JSON API
     */
    public static String watch(String className, String methodName, String watchExpression, int limit) {
        try {
            org.json.JSONObject result = watchWithParameters(className, methodName, limit);
            if (result.has("error")) {
                return "ERROR: " + result.getString("error");
            }
            return result.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Search for classes using Arthas sc command via HTTP API
     */
    public static String searchClass(String classNamePattern) {
        try {
            String pid = getFirstAvailablePid();
            if (pid.startsWith("ERROR") || pid.isEmpty()) {
                return "ERROR: No running JVM found. Please start a Java application first.";
            }

            // Attach if needed
            if (!activeSessions.containsKey(pid)) {
                attachByPid(pid);
            }

            // Add wildcards if not already present
            String pattern = classNamePattern;
            if (!pattern.contains("*")) {
                pattern = "*" + pattern + "*";
            }

            // Execute via HTTP API
            return executeTunnelApiCommand(pid, "sc " + pattern);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Search for methods using Arthas sm command via HTTP API
     */
    public static String searchMethod(String className, String methodPattern) {
        try {
            String pid = getFirstAvailablePid();
            if (pid.startsWith("ERROR") || pid.isEmpty()) {
                return "ERROR: No running JVM found. Please start a Java application first.";
            }

            // Attach if needed
            if (!activeSessions.containsKey(pid)) {
                attachByPid(pid);
            }

            // Add wildcards if not already present
            String pattern = methodPattern;
            if (!pattern.contains("*")) {
                pattern = "*" + pattern + "*";
            }

            // Execute via HTTP API
            return executeTunnelApiCommand(pid, "sm " + className + " " + pattern);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Get the first available JVM PID, preferring server1
     */
    private static String getFirstAvailablePid() {
        try {
            String jpsOutput = getJavaProcessList();

            // Parse jps -l output carefully - PID is first word, rest is classname
            java.util.List<java.util.Map.Entry<String, String>> pidList = new java.util.ArrayList<>();

            for (String line : jpsOutput.trim().split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("ERROR:")) continue;

                // Find first space - PID is before it
                int firstSpace = line.indexOf(' ');
                if (firstSpace > 0) {
                    String pid = line.substring(0, firstSpace).trim();
                    String className = line.substring(firstSpace + 1).trim();

                    // Skip known system processes
                    if (className.contains("jps") ||
                        className.contains("arthas-boot") ||
                        className.contains("GradleDaemon")) {
                        continue;
                    }

                    pidList.add(new java.util.AbstractMap.SimpleEntry<>(pid, className));
                    System.out.println("[ArthasManager] Found PID: " + pid + " -> " + className);
                }
            }

            // Priority 1: Find server1 explicitly
            for (java.util.Map.Entry<String, String> entry : pidList) {
                String className = entry.getValue();
                if (className.contains("server1") && !className.contains("server2")) {
                    System.out.println("[ArthasManager] ✓ Selected server1 PID: " + entry.getKey());
                    return entry.getKey();
                }
            }

            // Priority 2: Any non-agent-server application
            for (java.util.Map.Entry<String, String> entry : pidList) {
                String className = entry.getValue();
                if (!className.contains("agent-server")) {
                    System.out.println("[ArthasManager] ✓ Selected PID: " + entry.getKey() + " (" + className + ")");
                    return entry.getKey();
                }
            }

            System.out.println("[ArthasManager] ERROR: No suitable JVM found");
            return "ERROR: No suitable JVM processes found";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Get classloader information
     */
    public static String getClassLoaderInfo(String className) {
        try {
            String pid = findPidByClassName(className);
            if (pid.startsWith("ERROR")) {
                return pid;
            }

            ArthasSession session = activeSessions.get(pid);
            if (session == null) {
                attachByPid(pid);
                session = activeSessions.get(pid);
            }

            return session.executeCommand("classloader -t");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Find PID by class name using getJavaProcessList
     */
    public static String findPidByClassName(String className) {
        try {
            String jpsOutput = getJavaProcessList();
            String[] lines = jpsOutput.split("\n");

            for (String line : lines) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length >= 2) {
                    String processName = parts[1];
                    // Match class name (supports partial match)
                    if (processName.contains(className) ||
                        processName.contains(className.replace(".", "/")) ||
                        processName.toLowerCase().contains(className.toLowerCase())) {
                        return parts[0];
                    }
                }
            }
            return "ERROR: No JVM found with class name: " + className;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Detach Arthas from a JVM
     */
    public static String detach(String pid) {
        ArthasSession session = activeSessions.remove(pid);
        if (session != null) {
            session.stop();
            pidToSessionId.remove(pid);
            return "SUCCESS: Detached from PID " + pid;
        }
        return "ERROR: No session found for PID " + pid;
    }

    /**
     * Reset all Arthas sessions
     * Detaches from all attached JVMs and clears session state
     */
    public static String resetAllSessions() {
        int detachedCount = 0;
        StringBuilder result = new StringBuilder();

        result.append("[ArthasManager] Resetting all sessions...\n");

        for (Map.Entry<String, ArthasSession> entry : activeSessions.entrySet()) {
            String pid = entry.getKey();
            ArthasSession session = entry.getValue();

            try {
                session.stop();
                detachedCount++;
                result.append("  ✓ Detached from PID: ").append(pid).append("\n");
            } catch (Exception e) {
                result.append("  ✗ Failed to detach from PID: ").append(pid)
                      .append(" - ").append(e.getMessage()).append("\n");
            }
        }

        activeSessions.clear();
        pidToSessionId.clear();

        result.append("\n[ArthasManager] Reset complete: ").append(detachedCount)
              .append(" session(s) detached\n");
        result.append("[ArthasManager] All sessions cleared. Ready for fresh attach.\n");

        return result.toString();
    }

    /**
     * Get all active sessions
     */
    public static Map<String, ArthasSession> getActiveSessions() {
        return new ConcurrentHashMap<>(activeSessions);
    }

    /**
     * List all Java processes
     */
    public static java.util.List<Map<String, String>> listJavaProcesses() {
        java.util.List<Map<String, String>> processes = new java.util.ArrayList<>();
        try {
            String jpsOutput = getJavaProcessList();

            for (String line : jpsOutput.trim().split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("ERROR:")) continue;

                int firstSpace = line.indexOf(' ');
                if (firstSpace > 0) {
                    String pid = line.substring(0, firstSpace).trim();
                    String className = line.substring(firstSpace + 1).trim();

                    // Skip known system processes
                    if (className.contains("jps") ||
                        className.contains("arthas-boot") ||
                        className.contains("GradleDaemon") ||
                        className.contains("tasklist.exe")) {
                        continue;
                    }

                    processes.add(Map.of(
                            "pid", pid,
                            "className", className
                    ));
                }
            }
        } catch (Exception e) {
            // Return empty list on error
        }
        return processes;
    }

    /**
     * Get Java process list using jps or Windows tasklist
     */
    private static String getJavaProcessList() {
        try {
            // Try jps first
            String result = runCommand(new String[]{"jps", "-l"});
            if (!result.isEmpty() && !result.contains("not found")) {
                return result;
            }
        } catch (Exception e) {
            System.out.println("[ArthasManager] jps not available, trying alternative method");
        }

        // Fallback to Windows tasklist or PowerShell
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return getJavaProcessesWindows();
        } else {
            return getJavaProcessesUnix();
        }
    }

    /**
     * Get Java processes on Windows using tasklist
     */
    private static String getJavaProcessesWindows() {
        try {
            // Use tasklist to find java.exe processes
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                "tasklist /FI \"IMAGENAME eq java.exe\" /FO CSV /NH");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Parse CSV output: "java.exe","6036","Console","1","180,000 K"
                    if (line.contains("java.exe")) {
                        String[] parts = line.split(",");
                        if (parts.length >= 2) {
                            String pid = parts[1].replace("\"", "").trim();
                            // Get command line for class name
                            String cmdLine = getWindowsCommandLine(pid);
                            output.append(pid).append(" ").append(cmdLine).append("\n");
                        }
                    }
                }
            }

            process.waitFor(10, TimeUnit.SECONDS);
            return output.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Get command line for a Windows process
     */
    private static String getWindowsCommandLine(String pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "process", "where",
                "ProcessId=" + pid, "get", "CommandLine", "/format:list");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("CommandLine=")) {
                        String cmdLine = line.substring("CommandLine=".length());
                        // Extract jar name or main class
                        if (cmdLine.contains("jar ")) {
                            int jarIdx = cmdLine.lastIndexOf("jar ") + 4;
                            if (jarIdx > 3) {
                                String jarPath = cmdLine.substring(jarIdx).split(" ")[0];
                                return jarPath.substring(jarPath.lastIndexOf("\\") + 1);
                            }
                        } else if (cmdLine.contains(" ")) {
                            String[] parts = cmdLine.split(" ");
                            for (String part : parts) {
                                if (part.contains(".")) {
                                    return part.substring(part.lastIndexOf(".") + 1);
                                }
                            }
                        }
                        return cmdLine;
                    }
                }
            }

            process.waitFor(5, TimeUnit.SECONDS);
            return output.length() > 0 ? output.toString() : "java-process";
        } catch (Exception e) {
            return "java-process";
        }
    }

    /**
     * Get Java processes on Unix using ps
     */
    private static String getJavaProcessesUnix() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "ps -eo pid,command | grep java | grep -v grep");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.trim()).append("\n");
                }
            }

            process.waitFor(10, TimeUnit.SECONDS);
            return output.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static void ensureArthasAvailable() throws IOException {
        if (arthasHome != null && new File(arthasHome).exists()) {
            return;
        }

        // Use ~/.arthas if it exists
        Path arthasPath = Paths.get(System.getProperty("user.home"), ".arthas");
        if (Files.exists(arthasPath)) {
            arthasHome = arthasPath.toString();
            return;
        }

        // Create directory for Arthas
        arthasHome = Paths.get(System.getProperty("user.home"), ".arthas").toString();
        Files.createDirectories(Paths.get(arthasHome));
    }

    private static String runCommand(String[] command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return output.toString();
    }

    /**
     * Start async watch job - returns jobId immediately
     * @param className Target class name
     * @param methodName Target method name
     * @param limit Number of calls to capture
     * @return jobId for tracking
     */
    public static String startWatchAsync(String className, String methodName, int limit) {
        String jobId = "watch-" + System.currentTimeMillis();
        String pid = getFirstAvailablePid();

        if (pid.startsWith("ERROR") || pid.isEmpty()) {
            watchStatus.put(jobId, "FAILED");
            watchResults.put(jobId, new JSONObject().put("error", "No running JVM found"));
            return jobId;
        }

        watchStatus.put(jobId, "RUNNING");

        // Execute watch in background thread
        watchExecutor.submit(() -> {
            try {
                String watchCmd = String.format("watch %s %s params -n %d -x 1",
                    className, methodName, limit);

                System.out.println("[ArthasManager] Async watch started: " + jobId + " - " + watchCmd);

                String result = executeTunnelApiCommand(pid, watchCmd);
                JSONObject response = new JSONObject(result);

                // Parse response
                JSONArray parameters = new JSONArray();
                if (response.has("body")) {
                    JSONObject body = response.getJSONObject("body");
                    if (body.has("results")) {
                        JSONArray results = body.getJSONArray("results");
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject resultItem = results.getJSONObject(i);
                            if ("watch".equals(resultItem.optString("type"))) {
                                String value = resultItem.optString("value", "");
                                parameters = parseWatchValue(value);
                                break; // Use first watch result
                            }
                        }
                    }
                }

                JSONObject resultJson = new JSONObject();
                resultJson.put("success", true);
                resultJson.put("className", className);
                resultJson.put("methodName", methodName);
                resultJson.put("parameters", parameters);
                resultJson.put("parameterCount", parameters.length());

                watchResults.put(jobId, resultJson);
                watchStatus.put(jobId, "COMPLETED");
                System.out.println("[ArthasManager] Async watch completed: " + jobId + " - " + parameters.length() + " parameters");

            } catch (Exception e) {
                System.err.println("[ArthasManager] Async watch failed: " + jobId + " - " + e.getMessage());
                watchResults.put(jobId, new JSONObject().put("error", e.getMessage()));
                watchStatus.put(jobId, "FAILED");
            }
        });

        return jobId;
    }

    /**
     * Get watch result by jobId
     * @param jobId Job ID from startWatchAsync
     * @return Watch result with status
     */
    public static JSONObject getWatchResult(String jobId) {
        JSONObject result = new JSONObject();
        result.put("jobId", jobId);

        String status = watchStatus.getOrDefault(jobId, "UNKNOWN");
        result.put("status", status);

        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            JSONObject data = watchResults.get(jobId);
            if (data != null) {
                // Copy all fields from data to result
                for (String key : data.keySet()) {
                    result.put(key, data.get(key));
                }
            }
        }

        return result;
    }

    /**
     * Start async stack operation
     * @param jobId Job ID from startStackAsync
     * @return Stack result with status
     */
    public static String startStackAsync(String className, String methodName) {
        String jobId = "stack-" + System.currentTimeMillis();
        String pid = getFirstAvailablePid();

        if (pid.startsWith("ERROR") || pid.isEmpty()) {
            stackStatus.put(jobId, "FAILED");
            stackResults.put(jobId, "ERROR: No running JVM found");
            return jobId;
        }

        stackStatus.put(jobId, "RUNNING");

        // Execute stack in background thread
        stackExecutor.submit(() -> {
            try {
                String stackCmd = String.format("stack %s %s -n 1", className, methodName);

                System.out.println("[ArthasManager] Async stack started: " + jobId + " - " + stackCmd);

                String result = executeTunnelApiCommand(pid, stackCmd);

                stackResults.put(jobId, result);
                stackStatus.put(jobId, "COMPLETED");
                System.out.println("[ArthasManager] Async stack completed: " + jobId);

            } catch (Exception e) {
                System.err.println("[ArthasManager] Async stack failed: " + jobId + " - " + e.getMessage());
                stackResults.put(jobId, "ERROR: " + e.getMessage());
                stackStatus.put(jobId, "FAILED");
            }
        });

        return jobId;
    }

    /**
     * Get stack result by jobId
     * @param jobId Job ID from startStackAsync
     * @return Stack result with status
     */
    public static JSONObject getStackResult(String jobId) {
        JSONObject result = new JSONObject();
        result.put("jobId", jobId);

        String status = stackStatus.getOrDefault(jobId, "UNKNOWN");
        result.put("status", status);

        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            String data = stackResults.get(jobId);
            if (data != null) {
                result.put("result", data);
                result.put("success", !data.startsWith("ERROR"));
            }
        }

        return result;
    }

    /**
     * Start async trace operation
     * @param jobId Job ID from startTraceAsync
     * @return Trace result with status
     */
    public static String startTraceAsync(String className, String methodName) {
        String jobId = "trace-" + System.currentTimeMillis();
        String pid = getFirstAvailablePid();

        if (pid.startsWith("ERROR") || pid.isEmpty()) {
            traceStatus.put(jobId, "FAILED");
            traceResults.put(jobId, "ERROR: No running JVM found");
            return jobId;
        }

        traceStatus.put(jobId, "RUNNING");

        // Execute trace in background thread
        traceExecutor.submit(() -> {
            try {
                String traceCmd = String.format("trace %s %s -n 1", className, methodName);

                System.out.println("[ArthasManager] Async trace started: " + jobId + " - " + traceCmd);

                String result = executeTunnelApiCommand(pid, traceCmd);

                traceResults.put(jobId, result);
                traceStatus.put(jobId, "COMPLETED");
                System.out.println("[ArthasManager] Async trace completed: " + jobId);

            } catch (Exception e) {
                System.err.println("[ArthasManager] Async trace failed: " + jobId + " - " + e.getMessage());
                traceResults.put(jobId, "ERROR: " + e.getMessage());
                traceStatus.put(jobId, "FAILED");
            }
        });

        return jobId;
    }

    /**
     * Get trace result by jobId
     * @param jobId Job ID from startTraceAsync
     * @return Trace result with status
     */
    public static JSONObject getTraceResult(String jobId) {
        JSONObject result = new JSONObject();
        result.put("jobId", jobId);

        String status = traceStatus.getOrDefault(jobId, "UNKNOWN");
        result.put("status", status);

        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            String data = traceResults.get(jobId);
            if (data != null) {
                result.put("result", data);
                result.put("success", !data.startsWith("ERROR"));
            }
        }

        return result;
    }

    /**
     * Clean up old watch results (older than 5 minutes)
     */
    public static void cleanupOldWatchResults() {
        long fiveMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5);

        watchResults.entrySet().removeIf(entry -> {
            String jobId = entry.getKey();
            if (jobId.startsWith("watch-")) {
                long timestamp = Long.parseLong(jobId.substring(6));
                if (timestamp < fiveMinutesAgo) {
                    watchStatus.remove(jobId);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Arthas Session wrapper using Tunnel Server HTTP API
     */
    public static class ArthasSession {
        private final String sessionId;
        private final String pid;
        private final long startTime;
        private final List<String> commandHistory;

        public ArthasSession(String sessionId, String pid) {
            this.sessionId = sessionId;
            this.pid = pid;
            this.startTime = System.currentTimeMillis();
            this.commandHistory = new ArrayList<>();
        }

        public void start() {
            System.out.println("[Arthas] Session started: " + sessionId);
        }

        public void stop() {
            System.out.println("[Arthas] Session stopped: " + sessionId);
        }

        public String executeCommand(String command) {
            commandHistory.add(command);
            System.out.println("[Arthas] Executing: " + command);

            try {
                // Execute via Tunnel Server HTTP API
                return executeTunnelApiCommand(this.pid, command);
            } catch (Exception e) {
                return "ERROR executing command: " + e.getMessage();
            }
        }

        public String getSessionId() { return sessionId; }
        public String getPid() { return pid; }
        public long getStartTime() { return startTime; }
        public List<String> getCommandHistory() { return new ArrayList<>(commandHistory); }
    }
}
