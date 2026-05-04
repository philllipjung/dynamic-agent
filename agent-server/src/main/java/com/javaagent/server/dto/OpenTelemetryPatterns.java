package com.javaagent.server.dto;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OpenTelemetry Java Agent Auto-Instrumentation Patterns
 *
 * These patterns represent what OpenTelemetry Java Agent automatically instruments.
 * ByteBuddy/Arthas should avoid these areas to prevent conflicts.
 */
public class OpenTelemetryPatterns {

    // Package patterns that OTel auto-instruments
    public static final List<String> OTEL_PACKAGE_PATTERNS = Arrays.asList(
        // Spring Web
        ".*\\.controller\\..*",
        ".*\\.rest\\..*",
        ".*\\.endpoint\\..*",

        // Spring Data
        ".*\\.repository\\..*",
        ".*\\.dao\\..*",

        // Spring MVC
        ".*\\.web\\..*",
        ".*\\.servlet\\..*",

        // Messaging
        ".*\\.kafka\\..*",
        ".*\\.jms\\..*",
        ".*\\.rabbitmq\\..*"
    );

    // Class name suffixes that OTel auto-instruments
    public static final List<String> OTEL_CLASS_SUFFIXES = Arrays.asList(
        "Controller",
        "RestController",
        "Repository",
        "CrudRepository",
        "JpaRepository",
        "MongoRepository",
        "RestTemplate",
        "WebClient",
        "FeignClient",
        "KafkaTemplate",
        "JmsTemplate"
    );

    // Method name patterns that OTel auto-instruments
    public static final List<Pattern> OTEL_METHOD_PATTERNS = Arrays.asList(
        // Spring MVC
        Pattern.compile(".*Mapping"),  // getMapping, postMapping, etc.
        Pattern.compile("doGet"),
        Pattern.compile("doPost"),
        Pattern.compile("doFilter"),
        Pattern.compile("preHandle"),
        Pattern.compile("postHandle"),

        // JPA/Repository
        Pattern.compile("find.*"),     // findById, findAll, etc.
        Pattern.compile("save.*"),
        Pattern.compile("delete.*"),
        Pattern.compile("update.*"),
        Pattern.compile("count.*"),
        Pattern.compile("exists.*"),

        // JdbcTemplate
        Pattern.compile("execute.*"),
        Pattern.compile("query.*"),
        Pattern.compile("update.*"),

        // Messaging
        Pattern.compile("send.*"),
        Pattern.compile("receive.*"),
        Pattern.compile("listen.*")
    );

    // Annotation patterns (fully qualified names)
    public static final List<String> OTEL_ANNOTATIONS = Arrays.asList(
        // Spring Web
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
        "org.springframework.web.bind.annotation.PatchMapping",

        // Spring Data
        "org.springframework.stereotype.Repository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.mongodb.repository.MongoRepository",

        // Messaging
        "org.springframework.kafka.annotation.KafkaListener",
        "org.springframework.jms.annotation.JmsListener",
        "org.springframework.amqp.rabbit.annotation.RabbitListener",

        // Scheduling
        "org.springframework.scheduling.annotation.Scheduled",
        "org.springframework.scheduling.annotation.Async",

        // Transaction
        "org.springframework.transaction.annotation.Transactional"
    );

    /**
     * Check if a class is in OTel's auto-instrumentation scope
     */
    public static boolean isOtelClassScope(String className) {
        // Check package patterns
        for (String pattern : OTEL_PACKAGE_PATTERNS) {
            if (Pattern.matches(pattern, className)) {
                return true;
            }
        }

        // Check class suffixes
        for (String suffix : OTEL_CLASS_SUFFIXES) {
            if (className.endsWith(suffix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a method is in OTel's auto-instrumentation scope
     */
    public static boolean isOtelMethodScope(String methodName) {
        for (Pattern pattern : OTEL_METHOD_PATTERNS) {
            if (pattern.matcher(methodName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a method is likely auto-instrumented by OTel based on context
     */
    public static boolean isOtelAutoInstrumented(String className, String methodName) {
        // Controllers: all public methods are auto-instrumented
        if (className.endsWith("Controller") || className.endsWith("RestController")) {
            return true;
        }

        // Repositories: data access methods are auto-instrumented
        if (className.endsWith("Repository")) {
            return methodName.startsWith("find")
                || methodName.startsWith("save")
                || methodName.startsWith("delete")
                || methodName.startsWith("count")
                || methodName.startsWith("exists");
        }

        // Check class scope
        if (isOtelClassScope(className)) {
            return true;
        }

        // Check method scope
        if (isOtelMethodScope(methodName)) {
            return true;
        }

        return false;
    }

    /**
     * Get a description of why a class/method is in OTel scope
     */
    public static String getOtelScopeReason(String className, String methodName) {
        if (className.endsWith("Controller") || className.endsWith("RestController")) {
            return "OpenTelemetry auto-instruments all @Controller methods";
        }

        if (className.endsWith("Repository")) {
            return "OpenTelemetry auto-instruments all JPA repository methods";
        }

        if (className.contains(".controller.")) {
            return "OpenTelemetry auto-instruments controller package classes";
        }

        if (className.contains(".repository.")) {
            return "OpenTelemetry auto-instruments repository package classes";
        }

        if (methodName.startsWith("find") || methodName.startsWith("save")) {
            return "OpenTelemetry auto-instruments data access methods";
        }

        return "OpenTelemetry may auto-instrument this based on framework integration";
    }
}
