package com.javaagent.test.spark.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JobResponse {
    private String jobId;
    private String status;
    private String traceparent;
    private String traceId;
}
