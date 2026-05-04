package com.javaagent.test.spark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spark Test Microservice
 *
 * REST API for submitting Spark jobs with trace propagation
 */
@SpringBootApplication
public class SparkTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(SparkTestApplication.class, args);
    }
}
