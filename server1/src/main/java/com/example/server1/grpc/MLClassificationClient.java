package com.example.server1.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.example.server2.grpc.MLJobServiceGrpc;
import com.example.server2.grpc.ClassificationRequest;
import com.example.server2.grpc.JobResponse;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Component
public class MLClassificationClient {

    private static final Logger log = LoggerFactory.getLogger(MLClassificationClient.class);
    private static final String GRPC_SERVER_HOST = "localhost";
    private static final int GRPC_SERVER_PORT = 9094;

    private ManagedChannel channel;

    public Map<String, Object> submitClassificationJob(String jobId, String inputPath,
                                                         String outputPath, String modelType) {
        ManagedChannel channel = null;
        try {
            // Create gRPC channel
            channel = ManagedChannelBuilder
                    .forAddress(GRPC_SERVER_HOST, GRPC_SERVER_PORT)
                    .usePlaintext()
                    .build();

            // Create stub
            MLJobServiceGrpc.MLJobServiceBlockingStub stub = MLJobServiceGrpc.newBlockingStub(channel);

            // Build request
            ClassificationRequest request = ClassificationRequest
                    .newBuilder()
                    .setJobId(jobId != null ? jobId : "job-" + System.currentTimeMillis())
                    .setInputPath(inputPath != null ? inputPath : "/root/webflux-demo/spark-job/src/main/resources/classification_data.csv")
                    .setOutputPath(outputPath != null ? outputPath : "/tmp/predictions")
                    .setModelType(modelType != null ? modelType : "logistic")
                    .build();

            log.info("Calling gRPC server: {}:{} - JobId: {}", GRPC_SERVER_HOST, GRPC_SERVER_PORT, jobId);

            // Make gRPC call
            JobResponse response = stub.submitClassificationJob(request);

            log.info("gRPC call completed - Success: {}, Message: {}", response.getSuccess(), response.getMessage());

            // Build response map
            Map<String, Object> result = new HashMap<>();
            result.put("success", response.getSuccess());
            result.put("message", response.getMessage());
            result.put("job_id", response.getJobId());

            return result;

        } catch (Exception e) {
            log.error("gRPC call failed", e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", e.getMessage());
            return errorResult;
        } finally {
            if (channel != null) {
                try {
                    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
