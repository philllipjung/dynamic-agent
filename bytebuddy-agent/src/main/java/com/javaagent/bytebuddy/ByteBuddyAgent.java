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

            // Note: Helper classes are shaded into this JAR and loaded via injectHelper()
            // HelperClassInjector.prepareHelpers() is NOT needed for shaded JAR approach

            // CRITICAL: Process agent arguments for auto-instrumentation
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
        processAgentArgs(args);
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

}
