package com.javaagent.spark.instrumentation;

import com.javaagent.commons.config.OtelConfig;

import io.opentelemetry.api.trace.Span;
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

/**
 * Spark Job Auto-Instrumentation
 *
 * Parses traceparent from -Dtraceparent system property and creates
 * spans with the same Trace ID for distributed tracing continuity.
 *
 * Usage:
 *   java -Dtraceparent=00-traceid-spanid-01 -jar spark-job.jar
 */
public class SparkJobInstrumentation {

    static final Logger log = Logger.getLogger(SparkJobInstrumentation.class.getName());

    // Custom OpenTelemetrySdk instance for Spark Jobs
    public static volatile OpenTelemetrySdk customOpenTelemetry;
    public static volatile Tracer customTracer;

    /**
     * Get or create custom OpenTelemetry SDK instance
     */
    public static synchronized OpenTelemetrySdk getCustomOpenTelemetry() {
        if (customOpenTelemetry == null) {
            String otelEndpoint = OtelConfig.getOtelEndpoint();

            Logger logger = Logger.getLogger("SparkJobInstrumentation");
            logger.info("[SparkJobInstrumentation] Initializing custom OpenTelemetry SDK with endpoint: " + otelEndpoint);

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

            customTracer = customOpenTelemetry.getTracer("spark-job", "1.0.0");

            logger.info("[SparkJobInstrumentation] Custom OpenTelemetry SDK initialized successfully");
        }

        return customOpenTelemetry;
    }

    /**
     * Install instrumentation for Spark Job main methods
     */
    public static void install(Instrumentation instrumentation) {
        install(instrumentation, "com.example.spark.job.WordCountJob");
        install(instrumentation, "com.javaagent.test.spark.job.WordCountJob");
    }

    /**
     * Install instrumentation for specific class
     */
    public static void install(Instrumentation instrumentation, String targetClassName) {
        log.info("[SparkJobInstrumentation] Installing instrumentation for: " + targetClassName);

        new AgentBuilder.Default()
                .type(named(targetClassName))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            net.bytebuddy.utility.JavaModule module,
                                                            java.security.ProtectionDomain protectionDomain) {
                        return builder.visit(Advice.to(SparkJobAdvice.class).on(named("main")));
                    }
                })
                .installOn(instrumentation);

        log.info("[SparkJobInstrumentation] Instrumentation installed for: " + targetClassName);
    }

    /**
     * Advice for Spark Job main methods
     */
    public static class SparkJobAdvice {

        @Advice.OnMethodEnter
        public static void onMethodEnter(
                @Advice.Argument(0) String[] args,
                @Advice.Local("tracer") Tracer tracer,
                @Advice.Local("span") Span span,
                @Advice.Local("parentContext") Context parentContext,
                @Advice.Local("scope") io.opentelemetry.context.Scope scope) {

            Logger logger = Logger.getLogger("SparkJobInstrumentation");

            String traceParent = System.getProperty("traceparent", "");
            String jobName = System.getenv().getOrDefault("JOB_NAME", "spark-job");

            logger.info("[SparkJobInstrumentation] Job main entered");
            logger.info("[SparkJobInstrumentation] Job Name: " + jobName);
            logger.info("[SparkJobInstrumentation] Traceparent from -Dtraceparent: " + traceParent);

            // Get or create custom OpenTelemetry SDK
            OpenTelemetrySdk openTelemetry = getCustomOpenTelemetry();
            tracer = customTracer;

            // Parse traceparent and create parent context
            parentContext = Context.root();
            if (traceParent != null && !traceParent.isEmpty()) {
                try {
                    String[] parts = traceParent.split("-");
                    if (parts.length == 4 && parts[0].equals("00")) {
                        String traceId = parts[1];
                        String spanId = parts[2];

                        logger.info("[SparkJobInstrumentation] Parsed traceparent - TraceID: " + traceId + ", SpanID: " + spanId);

                        // Create SpanContext from parsed values
                        io.opentelemetry.api.trace.SpanContext parentSpanContext =
                            io.opentelemetry.api.trace.SpanContext.create(
                                traceId,
                                spanId,
                                io.opentelemetry.api.trace.TraceFlags.getSampled(),
                                io.opentelemetry.api.trace.TraceState.getDefault()
                            );

                        // Create non-recording parent span
                        Span parentSpan = Span.wrap(parentSpanContext);

                        // Build context with parent span
                        parentContext = Context.root().with(parentSpan);
                        logger.info("[SparkJobInstrumentation] Parent context created with TraceID: " + traceId);
                    }
                } catch (Exception e) {
                    logger.warning("[SparkJobInstrumentation] Failed to parse traceparent: " + e.getMessage());
                }
            }

            // Build span with parent context using custom SDK
            span = tracer.spanBuilder("spark.job")
                    .setParent(parentContext)
                    .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                    .startSpan();

            span.setAttribute("job.name", jobName);
            span.setAttribute("job.type", "spark");

            // Make span current
            scope = span.makeCurrent();

            logger.info("[SparkJobInstrumentation] Span created: " + span.getSpanContext().getSpanId());
            logger.info("[SparkJobInstrumentation] Trace ID: " + span.getSpanContext().getTraceId());
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onMethodExit(
                @Advice.Local("span") Span span,
                @Advice.Local("scope") io.opentelemetry.context.Scope scope,
                @Advice.Thrown Throwable throwable) {

            Logger logger = Logger.getLogger("SparkJobInstrumentation");

            if (span == null) {
                return;
            }

            try {
                if (throwable != null) {
                    span.recordException(throwable);
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, throwable.getMessage());
                    logger.severe("[SparkJobInstrumentation] Job failed: " + throwable.getMessage());
                } else {
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
                    logger.info("[SparkJobInstrumentation] Job completed successfully");
                }
            } finally {
                span.end();
                if (scope != null) {
                    scope.close();
                }
                logger.info("[SparkJobInstrumentation] Span ended");
            }
        }
    }
}
