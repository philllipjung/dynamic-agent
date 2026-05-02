package com.javaagent.test.spark.job;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WordCount Spark Job
 *
 * Test job for Spark instrumentation with trace continuity.
 *
 * Expected: When run with -Dtraceparent, creates span with same Trace ID
 */
public class WordCountJob {

    private static final Logger log = LoggerFactory.getLogger(WordCountJob.class);

    public static void main(String[] args) {

        log.info("========================================");
        log.info("  WordCount Spark Job");
        log.info("========================================");

        String traceParent = System.getProperty("traceparent", "");
        log.info("traceparent from -Dtraceparent: {}", traceParent);

        // Create Spark Session
        SparkSession spark = SparkSession.builder()
                .appName("WordCount")
                .master("local[*]")
                .config("spark.ui.enabled", "false")
                .getOrCreate();

        try {
            // Sample data
            Dataset<Row> data = spark.createDataFrame(
                new java.util.ArrayList<Object>() {{
                    add(new java.util.AbstractMap.SimpleEntry<>("text", "hello world hello spark"));
                    add(new java.util.AbstractMap.SimpleEntry<>("text", "spark tracing is awesome"));
                    add(new java.util.AbstractMap.SimpleEntry<>("text", "distributed tracing with spark"));
                }},
                java.util.Map.class
            );

            log.info("Input data: {} rows", data.count());

            // Word count logic
            Dataset<Row> wordCounts = data
                .select(org.apache.spark.sql.functions.explode(
                    org.apache.spark.sql.functions.split(
                        org.apache.spark.sql.functions.col("value"), " "
                    )
                ).as("word"))
                .filter(org.apache.spark.sql.functions.col("word").notEqual(""))
                .groupBy("word")
                .count();

            log.info("Word counts:");
            wordCounts.show();

            log.info("✓ WordCount completed successfully");

        } catch (Exception e) {
            log.error("WordCount job failed", e);
            throw e;
        } finally {
            spark.stop();
        }

        log.info("========================================");
        log.info("  Job finished");
        log.info("========================================");
    }
}
