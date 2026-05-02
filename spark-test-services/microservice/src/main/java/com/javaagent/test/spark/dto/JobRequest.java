package com.javaagent.test.spark.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JobRequest {
    private String jobName;
    private String inputPath;
    private String outputPath;
}
