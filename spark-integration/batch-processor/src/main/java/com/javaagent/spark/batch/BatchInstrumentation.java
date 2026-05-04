package com.javaagent.spark.batch;

import com.javaagent.commons.config.OtelConfig;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Batch Processing Auto-Instrumentation
 *
 * Creates spans with Span Links to original traces found in OpenSearch.
 * Links are created with FOLLOWS_FROM relationship.
 */
public class BatchInstrumentation {

    static final Logger log = Logger.getLogger(BatchInstrumentation.class.getName());

    // Custom OpenTelemetrySdk instance for Batch Processing
    public static volatile OpenTelemetrySdk customOpenTelemetry;
    public static volatile Tracer customTracer;

    /**
     * Java Agent premain - called when JVM starts with -javaagent
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        log.info("[BatchInstrumentation] ========================================");
        log.info("[BatchInstrumentation] Java Agent loaded via premain()");
        log.info("[BatchInstrumentation] Agent args: " + agentArgs);
        log.info("[BatchInstrumentation] ========================================");
        install(instrumentation);
    }

    /**
     * Java Agent agentmain - called when agent is attached to running JVM
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        log.info("[BatchInstrumentation] ========================================");
        log.info("[BatchInstrumentation] Java Agent attached via agentmain()");
        log.info("[BatchInstrumentation] Agent args: " + agentArgs);
        log.info("[BatchInstrumentation] ========================================");
        install(instrumentation);
    }

    /**
     * Get or create custom OpenTelemetry SDK instance
     */
    public static synchronized OpenTelemetrySdk getCustomOpenTelemetry() {
        if (customOpenTelemetry == null) {
            String otelEndpoint = OtelConfig.getOtelEndpoint();

            Logger logger = Logger.getLogger("BatchInstrumentation");
            logger.info("[BatchInstrumentation] Initializing custom OpenTelemetry SDK with endpoint: " + otelEndpoint);

            // Create OTLP span exporter
            OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(otelEndpoint)
                    .setTimeout(OtelConfig.getExporterTimeoutSeconds(), TimeUnit.SECONDS)
                    .build();

            // Create SDK Tracer Provider
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            // Build OpenTelemetry SDK
            customOpenTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .buildAndRegisterGlobal();

            customTracer = customOpenTelemetry.getTracer("batch-processor", "1.0.0");

            logger.info("[BatchInstrumentation] Custom OpenTelemetry SDK initialized successfully");
        }

        return customOpenTelemetry;
    }

    /**
     * Install instrumentation for batch processing
     */
    public static void install(Instrumentation instrumentation) {
        log.info("[BatchInstrumentation] Installing batch processing auto-instrumentation...");

        // OpenSearchBatchProcessor.executeBatch() instrumentation
        install(instrumentation, "com.javaagent.spark.batch.OpenSearchBatchProcessor");

        log.info("[BatchInstrumentation] Batch processing instrumentation installed successfully!");
    }

    /**
     * Install instrumentation for specific class
     */
    public static void install(Instrumentation instrumentation, String targetClassName) {
        log.info("[BatchInstrumentation] Installing instrumentation for: " + targetClassName);

        new AgentBuilder.Default()
                .type(named(targetClassName))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            net.bytebuddy.utility.JavaModule module,
                                                            java.security.ProtectionDomain protectionDomain) {
                        return builder
                                .visit(Advice.to(ExecuteBatchAdvice.class)
                                        .on(named("executeBatch")
                                                .and(takesArguments(3))));
                    }
                })
                .installOn(instrumentation);

        log.info("[BatchInstrumentation] Instrumentation installed for: " + targetClassName);
    }

    /**
     * Advice for executeBatch() method
     *
     * Creates span with link to original trace from OpenSearch
     */
    public static class ExecuteBatchAdvice {

        @Advice.OnMethodEnter
        public static void onMethodEnter(
                @Advice.Argument(0) String transactionNumber,
                @Advice.Argument(1) String batchType,
                @Advice.Argument(2) Object spanContextInfo,
                @Advice.Local("tracer") Tracer tracer,
                @Advice.Local("batchSpan") Span batchSpan,
                @Advice.Local("scope") io.opentelemetry.context.Scope scope) {

            Logger logger = Logger.getLogger("BatchInstrumentation");

            // Get custom OpenTelemetry SDK
            getCustomOpenTelemetry();
            tracer = customTracer;

            // Extract trace info from SpanContextInfo
            String traceId = null;
            String spanId = null;

            try {
                // Reflection to read SpanContextInfo fields
                java.lang.reflect.Field traceIdField = spanContextInfo.getClass().getDeclaredField("traceId");
                traceIdField.setAccessible(true);
                traceId = (String) traceIdField.get(spanContextInfo);

                java.lang.reflect.Field spanIdField = spanContextInfo.getClass().getDeclaredField("spanId");
                spanIdField.setAccessible(true);
                spanId = (String) spanIdField.get(spanContextInfo);

                logger.info("[BatchInstrumentation] Processing batch with Trace link");
                logger.info("  Transaction: " + transactionNumber);
                logger.info("  Batch Type: " + batchType);
                logger.info("  Linked Trace ID: " + traceId);
                logger.info("  Linked Span ID: " + spanId);

            } catch (Exception e) {
                logger.warning("[BatchInstrumentation] Failed to extract trace info: " + e.getMessage());
            }

            // Create Batch Span
            SpanContext parentSpanContext = null;
            if (traceId != null && spanId != null) {
                // Create SpanContext from OpenSearch results
                parentSpanContext = SpanContext.createFromRemoteParent(
                        traceId,
                        spanId,
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault()
                );
            }

            // Build Span
            io.opentelemetry.api.trace.SpanBuilder spanBuilder = tracer.spanBuilder("batch.execute")
                    .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL);

            if (parentSpanContext != null) {
                // Add Span Link!
                spanBuilder.addLink(parentSpanContext);
                logger.info("[BatchInstrumentation] Span link added to Trace: " + traceId);
            }

            // Start span
            batchSpan = spanBuilder.startSpan();

            // Set attributes
            batchSpan.setAttribute("transaction", transactionNumber);
            batchSpan.setAttribute("batch.type", batchType);

            if (traceId != null) {
                batchSpan.setAttribute("batch.linked.trace.id", traceId);
            }
            if (spanId != null) {
                batchSpan.setAttribute("batch.linked.span.id", spanId);
            }

            // Make span current
            scope = batchSpan.makeCurrent();

            logger.info("[BatchInstrumentation] Batch span created: " + batchSpan.getSpanContext().getSpanId());
            logger.info("[BatchInstrumentation] Trace ID: " + batchSpan.getSpanContext().getTraceId());
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onMethodExit(
                @Advice.Local("batchSpan") Span batchSpan,
                @Advice.Local("scope") io.opentelemetry.context.Scope scope,
                @Advice.Thrown Throwable throwable) {

            Logger logger = Logger.getLogger("BatchInstrumentation");

            if (batchSpan == null) {
                return;
            }

            try {
                if (throwable != null) {
                    batchSpan.recordException(throwable);
                    batchSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, throwable.getMessage());
                    logger.severe("[BatchInstrumentation] Batch execution failed: " + throwable.getMessage());
                } else {
                    batchSpan.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
                    logger.info("[BatchInstrumentation] Batch completed successfully");
                }
            } finally {
                batchSpan.end();
                if (scope != null) {
                    scope.close();
                }
                logger.info("[BatchInstrumentation] Batch span ended");
            }
        }
    }
}
