package com.javaagent.bytebuddy;

import com.javaagent.commons.AgentConstants;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaagent.bytebuddy.advices.SpanAdvice;

/**
 * ByteBuddy Java Agent for on-demand instrumentation
 * Supports: span creation, span attributes, span linking
 *
 * Note: bytebuddy-agent and bytebuddy-advice are already loaded in System ClassLoader
 * via agent-server dependencies, so additional classpath manipulation is not needed.
 */
public class ByteBuddyAgent {

    private static Instrumentation instrumentation;
    private static final Set<String> appliedMethods = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static AgentBuilder agentBuilder;
    private static com.sun.net.httpserver.HttpServer httpServer;

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[ByteBuddy] premain called with args: " + args);
        agentmain(args, inst);
        processAgentArgs(args);
    }

    public static void agentmain(String args, Instrumentation inst) {
        if (initialized.compareAndSet(false, true)) {
            instrumentation = inst;

            // CRITICAL: Initialize OpenTelemetry first
            try {
                initializeOpenTelemetry();
                System.out.println("[ByteBuddy] OpenTelemetry initialized");
            } catch (Exception e) {
                System.err.println("[ByteBuddy] Failed to initialize OpenTelemetry: " + e.getMessage());
                e.printStackTrace();
            }

            // Process agent arguments for auto-instrumentation
            processAgentArgs(args);

            agentBuilder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                     JavaModule javaModule, boolean loaded,
                                                     DynamicType dynamicType) {
                            System.out.println("[ByteBuddy] Transformed: " + typeDescription.getName());
                        }
                    });
            System.out.println("[ByteBuddy] Agent initialized");
        }

        // Always try to instrument filters (not just on first initialization)
        try {
            System.err.println("[ByteBuddy] Auto-instrumenting filters...");
            String result = instrumentFilters();
            System.err.println("[ByteBuddy] Auto-instrument result: " + result);
        } catch (Exception e) {
            System.err.println("[ByteBuddy] Failed to auto-instrument filters: " + e.getMessage());
            e.printStackTrace();
        }

        // Start HTTP server for remote control (every time agentmain is called)
        startHttpServer();

        processAgentArgs(args);
    }

    /**
     * Start simple HTTP server for remote control
     */
    private static void startHttpServer() {
        new Thread(() -> {
            try {
                int port = findAvailablePort(9999, 10010);
                httpServer = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(port), 0);

                // Register handlers
                httpServer.createContext("/api/health", exchange -> {
                    String response = "{\"status\":\"ok\",\"agent\":\"ByteBuddyAgent\"}";
                    exchange.sendResponseHeaders(200, response.length());
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.close();
                });

                httpServer.createContext("/api/instrumentFilters", exchange -> {
                    try {
                        String result = instrumentFilters();
                        String response = "{\"success\":" + result.startsWith("SUCCESS") +
                            ",\"message\":\"" + result.replace("\"", "'") + "\"}";
                        exchange.sendResponseHeaders(200, response.length());
                        exchange.getResponseBody().write(response.getBytes());
                    } catch (Exception e) {
                        String error = "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
                        exchange.sendResponseHeaders(500, error.length());
                        exchange.getResponseBody().write(error.getBytes());
                    }
                    exchange.close();
                });

                httpServer.createContext("/api/createSpan", exchange -> {
                    try {
                        // Read request body
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(exchange.getRequestBody()));
                        StringBuilder body = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            body.append(line);
                        }

                        // Parse JSON (simple parsing)
                        String className = extractJsonValue(body.toString(), "className");
                        String methodName = extractJsonValue(body.toString(), "methodName");

                        String result = createSpan(null, className, methodName);
                        String response = "{\"success\":" + result.startsWith("SUCCESS") +
                            ",\"message\":\"" + result.replace("\"", "'") + "\"}";
                        exchange.sendResponseHeaders(200, response.length());
                        exchange.getResponseBody().write(response.getBytes());
                    } catch (Exception e) {
                        String error = "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
                        exchange.sendResponseHeaders(500, error.length());
                        exchange.getResponseBody().write(error.getBytes());
                    }
                    exchange.close();
                });

                // Add createEventAdvice handler
                httpServer.createContext("/api/createEventAdvice", exchange -> {
                    try {
                        // Read request body
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(exchange.getRequestBody()));
                        StringBuilder body = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            body.append(line);
                        }

                        // Parse JSON (simple parsing)
                        String className = extractJsonValue(body.toString(), "className");
                        String methodName = extractJsonValue(body.toString(), "methodName");

                        String result = createEventAdvice(className, methodName);
                        String response = "{\"success\":" + result.startsWith("SUCCESS") +
                            ",\"message\":\"" + result.replace("\"", "'") + "\"}";
                        exchange.sendResponseHeaders(200, response.length());
                        exchange.getResponseBody().write(response.getBytes());
                    } catch (Exception e) {
                        String error = "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
                        exchange.sendResponseHeaders(500, error.length());
                        exchange.getResponseBody().write(error.getBytes());
                    }
                    exchange.close();
                });

                // Add detach handler
                httpServer.createContext("/api/detach", exchange -> {
                    try {
                        String result = detach();
                        String response = "{\"success\":" + result.startsWith("SUCCESS") +
                            ",\"message\":\"" + result.replace("\"", "'") + "\"}";
                        exchange.sendResponseHeaders(200, response.length());
                        exchange.getResponseBody().write(response.getBytes());
                    } catch (Exception e) {
                        String error = "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
                        exchange.sendResponseHeaders(500, error.length());
                        exchange.getResponseBody().write(error.getBytes());
                    }
                    exchange.close();
                });

                httpServer.setExecutor(null);
                httpServer.start();
                System.out.println("[ByteBuddy] HTTP server started on port " + port);
            } catch (Exception e) {
                System.err.println("[ByteBuddy] Failed to start HTTP server: " + e.getMessage());
                e.printStackTrace();
            }
        }, "ByteBuddy-HTTP-Server").start();
    }

    private static int findAvailablePort(int start, int end) {
        for (int port = start; port <= end; port++) {
            try {
                new java.net.Socket("localhost", port).close();
                // Port is in use, try next
            } catch (Exception e) {
                // Port is available
                return port;
            }
        }
        return start; // fallback
    }

    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Initialize OpenTelemetry with OTLP exporter
     */
    private static void initializeOpenTelemetry() {
        io.opentelemetry.sdk.OpenTelemetrySdk openTelemetry = io.opentelemetry.sdk.OpenTelemetrySdk.builder()
            .setTracerProvider(io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(
                    io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter.builder()
                        .setEndpoint("http://localhost:4317")
                        .build()
                ))
                .build())
            .buildAndRegisterGlobal();

        io.opentelemetry.api.trace.Tracer tracer = openTelemetry.getTracer("java-agent-bytebuddy");
        com.javaagent.bytebuddy.helper.SpanHelper.setTracer(tracer);
    }

    private static void processAgentArgs(String args) {
        if (args != null && args.startsWith("instrumentation=")) {
            // Support multiple instrumentations separated by ;
            String[] instructions = args.substring("instrumentation=".length()).split(";");
            for (String instruction : instructions) {
                String[] parts = instruction.trim().split(":");
                if (parts.length >= 3) {
                    String className = parts[0];
                    String methodName = parts[1];
                    String type = parts[2];

                    System.out.println("[ByteBuddy] Auto-instrumenting: " + className + "." + methodName + " (type: " + type + ")");

                    if ("span".equals(type)) {
                        String result = createSpan(null, className, methodName);
                        System.out.println("[ByteBuddy] " + result);
                    } else if ("spanAttribute".equals(type) && parts.length >= 4) {
                        // Parse parameter names: userId,sessionId
                        String[] params = parts[3].split(",");
                        java.util.Map<Integer, String> paramMapping = new java.util.LinkedHashMap<>();
                        int index = 0;
                        for (String param : params) {
                            paramMapping.put(index++, param.trim());
                        }
                        // Register parameter mapping in SpanAdvice
                        SpanAdvice.setParameterMapping(className, methodName, paramMapping);
                        System.out.println("[ByteBuddy] Parameter mapping registered for " + className + "." + methodName);

                        // CRITICAL: Also apply instrumentation (createSpan) for spanAttribute type
                        String result = createSpan(null, className, methodName);
                        System.out.println("[ByteBuddy] " + result);
                    }
                }
            }
        }
    }

    /**
     * Get instrumentation for external use
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * Create OpenTelemetry span for a method (uses direct instrumentation)
     *
     * @param pid Process ID of the target JVM (for compatibility, not used)
     * @param className Target class to instrument
     * @param methodName Target method to instrument
     * @return Result message
     */
    public static String createSpan(String pid, String className, String methodName) {
        try {
            if (instrumentation == null) {
                return "ERROR: Agent not initialized. Please attach to JVM first.";
            }

            String methodKey = className + "." + methodName;
            System.out.println("[ByteBuddy] Applying advice to " + methodKey);
            appliedMethods.add(methodKey);

            // CRITICAL: Load target class first
            Class<?> targetClass = Class.forName(className);
            ClassLoader targetClassLoader = targetClass.getClassLoader();
            System.out.println("[ByteBuddy] Target class loaded from: " + targetClassLoader);

            // CRITICAL: Inject SpanAdvice into target ClassLoader
            injectHelper(targetClassLoader);

            // Load Advice class from target's ClassLoader (now available after injection)
            Class<?> adviceClass = targetClassLoader.loadClass("com.javaagent.bytebuddy.advices.SpanAdvice");
            System.out.println("[ByteBuddy] Advice class loaded: " + adviceClass + " (loader: " + adviceClass.getClassLoader() + ")");

            // Create bytecode using ByteBuddy with Advice from same ClassLoader
            byte[] transformedBytes = new ByteBuddy()
                .redefine(targetClass)
                .visit(Advice.to(adviceClass)
                    .on(ElementMatchers.named(methodName)
                            .and(ElementMatchers.isMethod())
                            .and(ElementMatchers.not(ElementMatchers.isStatic()))))
                .make()
                .getBytes();

            System.out.println("[ByteBuddy] Transformed bytecode created, size: " + transformedBytes.length);

            // Use retransformation to apply the changes
            java.lang.instrument.ClassFileTransformer transformer = new java.lang.instrument.ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                      java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if (className.replace('/', '.').equals(targetClass.getName())) {
                        System.out.println("[ByteBuddy] *** RETRANSFORMING: " + className + " ***");
                        return transformedBytes;
                    }
                    return null;
                }
            };

            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(targetClass);
            instrumentation.removeTransformer(transformer);

            System.out.println("[ByteBuddy] *** ADVICE APPLIED TO " + methodKey + " ***");
            return "SUCCESS: Created span for " + methodKey;

        } catch (Exception e) {
            System.err.println("[ByteBuddy] Error: " + e.getMessage());
            e.printStackTrace();
            return "ERROR: Failed to create span - " + e.getMessage();
        }
    }

    /**
     * Inject helper classes into target ClassLoader using reflection
     * This solves ClassNotFound when advice executes in target thread context
     *
     * @param targetLoader The target's ClassLoader
     * @return true if injection succeeded, false otherwise
     */
    private static boolean injectHelper(ClassLoader targetLoader) {
        if (targetLoader == null) {
            System.err.println("[ByteBuddy] Target ClassLoader is null, cannot inject helper");
            return false;
        }

        try {
            for (String helperClassName : AgentConstants.HELPER_CLASSES) {
                // Check if class is already loaded in target ClassLoader
                try {
                    targetLoader.loadClass(helperClassName);
                    System.out.println("[ByteBuddy] Helper already loaded: " + helperClassName + ", skipping injection");
                    continue;
                } catch (ClassNotFoundException e) {
                    // Class not loaded, proceed with injection
                }

                // Load helper class bytecode from System ClassLoader
                byte[] classBytes = loadHelperClassBytes(helperClassName);
                if (classBytes == null) {
                    System.err.println("[ByteBuddy] Failed to load bytecode for " + helperClassName);
                    continue;
                }

                // Inject into target ClassLoader using reflection
                injectClassIntoLoader(targetLoader, helperClassName, classBytes);
                System.out.println("[ByteBuddy] Injected helper: " + helperClassName + " into ClassLoader: " + targetLoader);
            }

            return true;

        } catch (Exception e) {
            System.err.println("[ByteBuddy] Failed to inject helper classes: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Load helper class bytecode from agent's ClassLoader
     */
    private static byte[] loadHelperClassBytes(String className) {
        try {
            String path = className.replace('.', '/') + ".class";
            java.io.InputStream is = ClassLoader.getSystemClassLoader().getResourceAsStream(path);
            if (is == null) {
                // Try from current class's ClassLoader
                is = ByteBuddyAgent.class.getClassLoader().getResourceAsStream(path);
            }

            if (is == null) {
                System.err.println("[ByteBuddy] Helper class file not found: " + path);
                return null;
            }

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            int bytesRead;
            byte[] data = new byte[4096];
            while ((bytesRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            is.close();

            return buffer.toByteArray();

        } catch (Exception e) {
            System.err.println("[ByteBuddy] Error loading helper bytecode for " + className + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Inject a class into target ClassLoader using reflection
     * Uses protected ClassLoader.defineClass() method
     */
    private static void injectClassIntoLoader(ClassLoader targetLoader, String className, byte[] classBytes) throws Exception {
        java.lang.reflect.Method defineClass = ClassLoader.class.getDeclaredMethod(
            "defineClass", String.class, byte[].class, int.class, int.class
        );
        defineClass.setAccessible(true);
        defineClass.invoke(targetLoader, className, classBytes, 0, classBytes.length);
    }

    /**
     * Instrument Spring Filter classes to capture HTTP request headers and body
     * Uses EventAdvice to print request information
     *
     * @return Result message
     */
    public static String instrumentFilters() {
        try {
            if (instrumentation == null) {
                return "ERROR: Agent not initialized. Please attach to JVM first.";
            }

            System.err.println("[ByteBuddy] Instrumenting Spring Filters...");

            // Debug: Print total class count
            Class<?>[] allClasses = instrumentation.getAllLoadedClasses();
            System.err.println("[ByteBuddy] Total loaded classes: " + allClasses.length);

            // Check if jakarta.servlet.Filter is available
            boolean jakartaAvailable = false;
            try {
                Class.forName("jakarta.servlet.Filter");
                jakartaAvailable = true;
                System.err.println("[ByteBuddy] jakarta.servlet.Filter is available");
            } catch (ClassNotFoundException e) {
                System.err.println("[ByteBuddy] jakarta.servlet.Filter is NOT available");
            }

            int filterCount = 0;
            int checkedCount = 0;

            // Find all Filter classes and related classes
            for (Class<?> clazz : allClasses) {
                checkedCount++;

                // Debug: Print every 1000th class to see progress
                if (checkedCount % 1000 == 0) {
                    System.err.println("[ByteBuddy] Checked " + checkedCount + " classes so far...");
                }

                try {
                    String className = clazz.getName();

                    // Check for javax.servlet.Filter
                    if (javax.servlet.Filter.class.isAssignableFrom(clazz)) {
                        System.err.println("[ByteBuddy] Found javax.servlet.Filter: " + className);
                        filterCount++;
                        try {
                            instrumentSingleFilter(clazz);
                        } catch (Exception e) {
                            System.err.println("[ByteBuddy] Failed to instrument " + className + ": " + e.getMessage());
                        }
                        continue;
                    }

                    // Check for jakarta.servlet.Filter (Spring Boot 3.x)
                    if (jakartaAvailable) {
                        Class<?> jakartaFilter = Class.forName("jakarta.servlet.Filter");
                        if (jakartaFilter.isAssignableFrom(clazz)) {
                            System.err.println("[ByteBuddy] Found jakarta.servlet.Filter: " + className);
                            filterCount++;
                            try {
                                instrumentSingleFilter(clazz);
                            } catch (Exception e) {
                                System.err.println("[ByteBuddy] Failed to instrument " + className + ": " + e.getMessage());
                            }
                            continue;
                        }
                    }

                    // Check for Spring Filter classes (OncePerRequestFilter, etc.)
                    if (className.contains("Filter") &&
                        (className.contains("org.springframework.web.filter") ||
                         className.contains("org.apache.catalina.filters") ||
                         className.contains("com.javaagent.server.filter"))) {
                        System.err.println("[ByteBuddy] Found potential Filter class: " + className);
                        filterCount++;
                        try {
                            instrumentSingleFilter(clazz);
                        } catch (Exception e) {
                            System.err.println("[ByteBuddy] Failed to instrument " + className + ": " + e.getMessage());
                        }
                        continue;
                    }

                } catch (ClassNotFoundException e) {
                    // Class not available, skip
                } catch (NoClassDefFoundError e) {
                    // Class not available, skip
                }
            }

            System.err.println("[ByteBuddy] Checked " + checkedCount + " classes total");
            System.err.println("[ByteBuddy] Total filters found: " + filterCount);

            if (filterCount == 0) {
                // Debug: Search for Filter-related classes
                System.err.println("[ByteBuddy] Searching for Filter-related classes...");
                int foundFilter = 0;
                for (Class<?> clazz : allClasses) {
                    String className = clazz.getName();
                    if (className.toLowerCase().contains("filter")) {
                        System.err.println("[ByteBuddy]   - " + className);
                        foundFilter++;
                        if (foundFilter >= 30) break; // Limit output
                    }
                }
                return "WARNING: No filters found to instrument";
            }

            return "SUCCESS: Filter instrumentation completed (" + filterCount + " filters)";

        } catch (Exception e) {
            System.err.println("[ByteBuddy] Error: " + e.getMessage());
            e.printStackTrace();
            return "ERROR: Failed to instrument filters - " + e.getMessage();
        }
    }

    /**
     * Instrument a single Filter class
     */
    private static void instrumentSingleFilter(Class<?> filterClass) throws Exception {
        String className = filterClass.getName();
        ClassLoader targetClassLoader = filterClass.getClassLoader();

        System.out.println("[ByteBuddy] Instrumenting filter: " + className);

        // Inject helper classes
        injectHelper(targetClassLoader);

        // Load EventAdvice class
        Class<?> adviceClass = targetClassLoader.loadClass("com.javaagent.bytebuddy.advices.EventAdvice");
        System.out.println("[ByteBuddy] EventAdvice loaded: " + adviceClass);

        // Create bytecode using ByteBuddy
        // Match doFilter(ServletRequest, ServletResponse, FilterChain) - takes 3 arguments
        byte[] transformedBytes = new ByteBuddy()
            .redefine(filterClass)
            .visit(Advice.to(adviceClass)
                .on(ElementMatchers.named("doFilter")
                        .and(ElementMatchers.isMethod())
                        .and(ElementMatchers.not(ElementMatchers.isStatic()))
                        .and(ElementMatchers.takesArguments(3))))
            .make()
            .getBytes();

        // Apply transformation
        java.lang.instrument.ClassFileTransformer transformer = new java.lang.instrument.ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                  java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className.replace('/', '.').equals(filterClass.getName())) {
                    System.out.println("[ByteBuddy] *** RETRANSFORMING FILTER: " + className + " ***");
                    return transformedBytes;
                }
                return null;
            }
        };

        instrumentation.addTransformer(transformer, true);
        instrumentation.retransformClasses(filterClass);
        instrumentation.removeTransformer(transformer);

        System.out.println("[ByteBuddy] *** KERNEL ADVICE APPLIED TO " + className + " ***");
    }

    /**
     * Create span attribute advice for a method
     * Captures method parameters as OpenTelemetry span attributes
     *
     * @param pid Process ID (not used, for compatibility)
     * @param className Target class name
     * @param methodName Target method name
     * @param paramMapping Parameter index to name mapping
     * @return Result message
     */
    public static String createSpanAttributeAdvice(String pid, String className, String methodName,
                                                   java.util.Map<Integer, String> paramMapping) {
        try {
            if (instrumentation == null) {
                return "ERROR: Agent not initialized. Please attach to JVM first.";
            }

            String methodKey = className + "." + methodName;
            System.out.println("[ByteBuddy] Applying span attribute advice to " + methodKey);

            // Register parameter mapping in SpanAdvice
            SpanAdvice.setParameterMapping(className, methodName, paramMapping);

            // Load target class
            Class<?> targetClass = Class.forName(className);
            ClassLoader targetClassLoader = targetClass.getClassLoader();

            // Inject helper classes
            injectHelper(targetClassLoader);

            // Load SpanAdvice class
            Class<?> adviceClass = targetClassLoader.loadClass("com.javaagent.bytebuddy.advices.SpanAdvice");

            // Create bytecode using ByteBuddy
            byte[] transformedBytes = new ByteBuddy()
                .redefine(targetClass)
                .visit(Advice.to(adviceClass)
                    .on(ElementMatchers.named(methodName)
                            .and(ElementMatchers.isMethod())
                            .and(ElementMatchers.not(ElementMatchers.isStatic()))))
                .make()
                .getBytes();

            // Apply transformation
            java.lang.instrument.ClassFileTransformer transformer = new java.lang.instrument.ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                      java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if (className.replace('/', '.').equals(targetClass.getName())) {
                        System.out.println("[ByteBuddy] *** RETRANSFORMING: " + className + " ***");
                        return transformedBytes;
                    }
                    return null;
                }
            };

            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(targetClass);
            instrumentation.removeTransformer(transformer);

            System.out.println("[ByteBuddy] *** SPAN ATTRIBUTE ADVICE APPLIED TO " + methodKey + " ***");
            return "SUCCESS: Created span attribute advice for " + methodKey;

        } catch (Exception e) {
            System.err.println("[ByteBuddy] Error: " + e.getMessage());
            e.printStackTrace();
            return "ERROR: Failed to create span attribute advice - " + e.getMessage();
        }
    }

    /**
     * Create event advice for capturing HTTP request events
     * Enables HTTP request/response capture in JSON format
     *
     * @param className Target class name
     * @param methodName Target method name (e.g., "doService")
     * @return Result message
     */
    public static String createEventAdvice(String className, String methodName) {
        try {
            if (instrumentation == null) {
                return "ERROR: Agent not initialized. Please attach to JVM first.";
            }

            String methodKey = className + "." + methodName;
            System.out.println("[ByteBuddy] Applying event advice to " + methodKey);

            // Load target class - search through instrumentation first for Spring Boot apps
            final Class<?> targetClass;
            final ClassLoader targetClassLoader;

            // Try to find the class in already loaded classes (for Spring Boot apps)
            Class<?> foundClass = null;
            ClassLoader foundLoader = null;

            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (clazz.getName().equals(className)) {
                    foundClass = clazz;
                    foundLoader = clazz.getClassLoader();
                    System.out.println("[ByteBuddy] Found class in loaded classes: " + className + " (loader: " + foundLoader + ")");
                    break;
                }
            }

            // If not found, try Class.forName as fallback
            if (foundClass == null) {
                System.out.println("[ByteBuddy] Class not found in loaded classes, trying Class.forName");
                foundClass = Class.forName(className);
                foundLoader = foundClass.getClassLoader();
            }

            targetClass = foundClass;
            targetClassLoader = foundLoader;

            // Inject helper classes
            injectHelper(targetClassLoader);

            // Load EventAdvice class
            Class<?> adviceClass = targetClassLoader.loadClass("com.javaagent.bytebuddy.advices.EventAdvice");

            // Create bytecode using ByteBuddy
            byte[] transformedBytes = new ByteBuddy()
                .redefine(targetClass)
                .visit(Advice.to(adviceClass)
                    .on(ElementMatchers.named(methodName)
                            .and(ElementMatchers.isMethod())
                            .and(ElementMatchers.not(ElementMatchers.isStatic()))))
                .make()
                .getBytes();

            // Apply transformation
            java.lang.instrument.ClassFileTransformer transformer = new java.lang.instrument.ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                      java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if (className.replace('/', '.').equals(targetClass.getName())) {
                        System.out.println("[ByteBuddy] *** RETRANSFORMING: " + className + " ***");
                        return transformedBytes;
                    }
                    return null;
                }
            };

            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(targetClass);
            instrumentation.removeTransformer(transformer);

            System.out.println("[ByteBuddy] *** EVENT ADVICE APPLIED TO " + methodKey + " ***");
            return "SUCCESS: Created event advice for " + methodKey;

        } catch (Exception e) {
            System.err.println("[ByteBuddy] Error: " + e.getMessage());
            e.printStackTrace();
            return "ERROR: Failed to create event advice - " + e.getMessage();
        }
    }

    /**
     * Stop the HTTP server
     */
    public static void stopHttpServer() {
        if (httpServer != null) {
            try {
                httpServer.stop(0);
                System.out.println("[ByteBuddy] HTTP server stopped");
            } catch (Exception e) {
                System.err.println("[ByteBuddy] Failed to stop HTTP server: " + e.getMessage());
            }
        }
    }

    /**
     * Detach the ByteBuddy agent
     * Stops HTTP server and clears applied methods tracking
     * Note: Instrumentation remains in the target JVM until it restarts
     *
     * @return Result message
     */
    public static String detach() {
        try {
            stopHttpServer();
            appliedMethods.clear();
            initialized.set(false);
            return "SUCCESS: Detached (instrumentation remains in target JVM)";
        } catch (Exception e) {
            return "ERROR: Failed to detach - " + e.getMessage();
        }
    }

}
