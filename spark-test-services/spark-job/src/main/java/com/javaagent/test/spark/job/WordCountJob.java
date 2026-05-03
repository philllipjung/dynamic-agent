package com.javaagent.test.spark.job;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.*;
import static org.apache.spark.sql.functions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

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
            // Define schema
            StructType schema = new StructType(new StructField[]{
                new StructField("text", DataTypes.StringType, false, Metadata.empty())
            });

            // Create sample data
            List<Row> data = Arrays.asList(
                RowFactory.create("hello world hello spark"),
                RowFactory.create("spark tracing is awesome"),
                RowFactory.create("distributed tracing with spark")
            );

            Dataset<Row> textData = spark.createDataFrame(data, schema);

            log.info("Input data: {} rows", textData.count());

            // Word count logic
            Dataset<Row> wordCounts = textData
                .select(explode(split(col("text"), " ")).as("word"))
                .filter(col("word").notEqual(""))
                .groupBy("word")
                .count()
                .orderBy(col("count").desc());

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
