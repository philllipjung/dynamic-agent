# Service Performance Monitoring (SPM) - OpenSearch Backend Setup

This document describes the SPM monitoring setup using **Option 2: Direct OpenSearch Querying** architecture.

## Architecture Overview

This deployment uses **Option 2 (Direct OpenSearch Querying)** from the [Jaeger SPM documentation](https://www.jaegertracing.io/docs/latest/spm/):

- **No Prometheus required** - Jaeger computes metrics on-the-fly from OpenSearch
- **No SpanMetrics Connector** - Simpler configuration, no pre-aggregation
- **Direct OTLP to Jaeger** - Applications send traces directly to Jaeger v2
- **Single Storage Backend** - OpenSearch stores both traces and metrics data

```
┌─────────────┐     OTLP      ┌─────────┐     ┌─────────────┐
│  Apps       │ ────────────> │ Jaeger  │ ──> │ OpenSearch  │
│ (server1/2) │               │ (v2)    │     │             │
└─────────────┘               └─────────┘     └─────────────┘
                                      ^              |
                                      |              |
                                      v              v
                              ┌─────────────┐   ┌──────────────┐
                              │ Jaeger UI   │   │ OpenSearch   │
                              │             │   │ Dashboards   │
                              └─────────────┘   └──────────────┘
```

## Quickstart

### Start Monitoring Stack (OpenSearch Only)

```bash
cd /root/jaeger/docker-compose/monitor
docker-compose -f docker-compose-opensearch.yml up -d
```

**Access Points:**
- **Jaeger UI:** http://localhost:16686
- **OpenSearch Dashboards:** http://localhost:5601
- **OpenSearch API:** http://localhost:9200

## Application Ports

| Service | Port | Protocol | Description |
|---------|------|----------|-------------|
| **React Client** | 5173 | HTTP | Frontend UI |
| **Server1** | 8081 | HTTP | REST API Gateway |
| **Server2** | 8082 | HTTP | REST API + Kafka Consumer |
| **Server2** | 9094 | gRPC | ML Classification Service ⚠️ |
| **Kafka** | 9092 | TCP | Message Broker |

⚠️ **Important:** Server2's gRPC port changed from 9090 to 9094 due to port conflicts with Prometheus.

## Monitoring Stack Ports

| Component | Port | Description |
|-----------|------|-------------|
| **Jaeger UI** | 16686 | Web UI for trace visualization and SPM metrics |
| **Jaeger OTLP** | 4317 | gRPC OTLP Receiver (traces) |
| **Jaeger OTLP** | 4318 | HTTP OTLP Receiver (traces) |
| **OpenSearch** | 9200 | Search engine for trace storage |
| **OpenSearch Dashboards** | 5601 | Data visualization UI |

## SPM Metrics (Direct OpenSearch Querying)

With Option 2 architecture, Jaeger computes metrics on-the-fly from OpenSearch when you query them.

### Available Metrics

Jaeger SPM provides RED (Rate, Errors, Duration) metrics:

| Metric | Type | Description |
|--------|------|-------------|
| **Rate** | Counter | Request rate per service/operation |
| **Errors** | Counter | Error rate per service/operation |
| **Duration** | Histogram | Latency percentiles (P50, P95, P99) |

### How to Query Metrics

**Via Jaeger UI:**
1. Navigate to http://localhost:16686
2. Click the **Monitor** tab
3. Select services and operations
4. View RED metrics in real-time

**Via Jaeger HTTP API:**
```bash
# Get latency metrics for a service
curl "http://localhost:16686/api/metrics/latencies?service=server1&quantile=0.95"

# Get call metrics
curl "http://localhost:16686/api/metrics/calls?service=server1"

# Get error metrics
curl "http://localhost:16686/api/metrics/errors?service=server1"
```

### Connection Types

Service dependencies detected in traces:
- **Unset:** Direct HTTP/gRPC calls between services
- `messaging_system`: Kafka messaging
- `virtual_node`: Uninstrumented services (e.g., external databases)

### Current Service Dependencies

```
user → server1 (virtual_node: client)
server1 → server2 (HTTP REST)
server2 → server2 (messaging_system: Kafka)
java-client → server1
customer → mysql (virtual_node)
driver → redis (virtual_node)
```

## Configuration Files

| File | Description |
|------|-------------|
| `docker-compose-opensearch.yml` | Monitoring stack with OpenSearch backend |
| `config-spm-opensearch.yaml` | Jaeger v2 configuration (Option 2) |
| `jaeger-ui.json` | Jaeger UI configuration |

### Docker Compose File

**File:** `docker-compose-opensearch.yml`

```yaml
services:
  opensearch:
    image: opensearchproject/opensearch:3.5.0@sha256:2f2244c7c0ad3a4a0d09977c2b519977b77bacc92e1eaf995c080c1d22f517b6
    networks:
      - backend
    environment:
      - discovery.type=single-node
      - plugins.security.disabled=true
      - http.host=0.0.0.0
      - transport.host=127.0.0.1
      - OPENSEARCH_INITIAL_ADMIN_PASSWORD=passRT%^#234
    ports:
      - "9200:9200"
    healthcheck:
      test: [ "CMD-SHELL", "curl -f http://localhost:9200 || exit 1" ]
      interval: 10s
      timeout: 10s
      retries: 30

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:3.5.0
    networks:
      - backend
    environment:
      - OPENSEARCH_HOSTS=["http://opensearch:9200"]
      - DISABLE_SECURITY_DASHBOARDS_PLUGIN=true
    ports:
      - "5601:5601"
    depends_on:
      opensearch:
        condition: service_healthy
    healthcheck:
      test: [ "CMD-SHELL", "curl -f http://localhost:5601/api/status || exit 1" ]
      interval: 10s
      timeout: 10s
      retries: 30

  jaeger:
    networks:
      - backend
    image: jaegertracing/jaeger:${JAEGER_VERSION:-latest}
    volumes:
      - "./jaeger-ui.json:/etc/jaeger/jaeger-ui.json"
      - "../../cmd/jaeger/config-spm-opensearch.yaml:/etc/jaeger/config.yml"
    command: ["--config", "/etc/jaeger/config.yml"]
    ports:
      - "16686:16686" # Jaeger UI http://localhost:16686
      - "4317:4317"   # OTLP gRPC receiver
      - "4318:4318"   # OTLP HTTP receiver
    depends_on:
      opensearch:
        condition: service_healthy

  microsim:
    networks:
      - backend
    image: yurishkuro/microsim:v0.6.0@sha256:fd75a9b3dd1bb4d7d305a562edeac60051a7fec784b898ff7ab834eacc73f41e
    command: "-d 24h -s 500ms"
    environment:
      - OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://jaeger:4318/v1/traces
    depends_on:
      - jaeger

networks:
  backend:

volumes:
  esdata:
    driver: local
```

**Services Summary:**

| Service | Image | Ports | Description |
|---------|-------|-------|-------------|
| opensearch | opensearchproject/opensearch:3.5.0 | 9200 | Trace & metrics storage |
| opensearch-dashboards | opensearchproject/opensearch-dashboards:3.5.0 | 5601 | Visualization UI |
| jaeger | jaegertracing/jaeger:latest | 16686, 4317-4318 | Trace processing, SPM & UI |
| microsim | yurishkuro/microsim:v0.6.0 | - | Trace simulator |

## Known Issues and Solutions

### Issue 1: Server2 gRPC Port Configuration

**Symptoms:**
- `ml.MLJobService/SubmitClassificationJob` spans have ERROR status
- HTTP 500 errors on `/api/ml/classification`

**Root Cause:**
gRPC port was misconfigured. Server1's `MLClassificationClient.java` was configured to use port 9090, but:
- Port 9090 was previously used by Prometheus
- Server2's gRPC server runs on port 9094

**Solution:**
Updated `MLClassificationClient.java`:
```java
private static final int GRPC_SERVER_PORT = 9094;  // Changed from 9090
```

Also updated `/root/webflux-demo/server2/src/main/resources/application.yml`:
```yaml
grpc:
  server:
    port: 9094  # Changed from 9090
```

### Issue 2: Metrics Computation On-Demand

**Symptoms:**
- Jaeger UI Monitor tab may show "no data" initially
- Metrics appear to be delayed

**Root Cause:**
With Option 2 (direct OpenSearch querying), Jaeger computes metrics on-demand when you query them. This is different from Prometheus which pre-aggregates metrics.

**Solution:**
- Wait for traces to be indexed in OpenSearch (~5-10 seconds)
- Refresh the Monitor tab
- Metrics are computed dynamically from trace data

### Issue 3: jaeger-dependency-* Metrics Not Available

**Symptoms:**
- `jaeger-dependency-*` metrics don't exist

**Root Cause:**
Spark-dependencies tool is not compatible with OpenSearch 3.5.0 (only supports Elasticsearch up to 7.x).

**Solution:**
Jaeger v2 with SPM provides dependency information through the Service Graph API. Use Jaeger UI or HTTP API to query service dependencies.

## Testing SPM Metrics

### Generate Test Traces

1. **HTTP REST calls:**
```bash
curl http://localhost:8081/api/hello
```

2. **gRPC calls:**
```bash
curl -X POST http://localhost:8081/api/ml/classification \
  -H "Content-Type: application/json" \
  -d '{"job_id":"test-123"}'
```

3. **Generate multiple requests:**
```bash
for i in {1..10}; do
  curl http://localhost:8081/api/hello
  curl -X POST http://localhost:8081/api/ml/classification \
    -H "Content-Type: application/json" \
    -d "{\"job_id\":\"test-$i\"}"
  sleep 0.5
done
```

### Verify Metrics in Jaeger

Wait 10-20 seconds for traces to be indexed, then verify:

1. **Check Jaeger UI Monitor Tab:**
   - Navigate to http://localhost:16686
   - Click **Monitor** tab
   - Select services and operations
   - View RED metrics (Rate, Errors, Duration)

2. **Query via HTTP API:**
```bash
# Get latency metrics
curl "http://localhost:16686/api/metrics/latencies?service=server1&quantile=0.95"

# Get call metrics
curl "http://localhost:16686/api/metrics/calls?service=server1"

# Get error metrics
curl "http://localhost:16686/api/metrics/errors?service=server1"
```

## Visualization

### Jaeger UI (Primary Interface)
**URL:** http://localhost:16686

**Search Tab:**
- View individual traces
- Trace timeline visualization
- Filter by service, operation, tags, duration
- View service dependencies

**Monitor Tab (SPM):**
- RED metrics (Rate, Errors, Duration)
- Latency percentiles (P50, P95, P99)
- Service-level and operation-level metrics
- Time range selection

### OpenSearch Dashboards
**URL:** http://localhost:5601

- Index patterns: `jaeger-*`
- Raw trace data exploration
- Create custom dashboards
- Direct OpenSearch queries

## HTTP API Specification

### Query Metrics

`/api/metrics/{metric_type}?{query}`

Where (Backus-Naur form):

```
metric_type = 'latencies' | 'calls' | 'errors'

query = services , [ '&' optionalParams ]

optionalParams = param | param '&' optionalParams

param = groupByOperation | quantile | endTs | lookback | step | ratePer | spanKinds

services = service | service '&' services
service = 'service=' strValue
  - The list of services to include in the metrics selection filter, which are logically 'OR'ed.
  - Mandatory.

quantile = 'quantile=' floatValue
  - The quantile to compute the latency 'P' value. Valid range (0,1].
  - Mandatory for 'latencies' type.

groupByOperation = 'groupByOperation=' boolValue
boolValue = '1' | 't' | 'T' | 'true' | 'TRUE' | 'True' | 0 | 'f' | 'F' | 'false' | 'FALSE' | 'False'
  - A boolean value which will determine if the metrics query will also group by operation.
  - Optional with default: false

endTs = 'endTs=' intValue
  - The posix milliseconds timestamp of the end time range of the metrics query.
  - Optional with default: now

lookback = 'lookback=' intValue
  - The duration, in milliseconds, from endTs to look back on for metrics data points.
  - For example, if set to `3600000` (1 hour), the query would span from `endTs - 1 hour` to `endTs`.
  - Optional with default: 3600000 (1 hour).

step = 'step=' intValue
  - The duration, in milliseconds, between data points of the query results.
  - For example, if set to 5s, the results would produce a data point every 5 seconds from the `endTs - lookback` to `endTs`.
  - Optional with default: 5000 (5 seconds).

ratePer = 'ratePer=' intValue
  - The duration, in milliseconds, in which the per-second rate of change is calculated for a cumulative counter metric.
  - Optional with default: 600000 (10 minutes).

spanKinds = spanKind | spanKind '&' spanKinds
spanKind = 'spanKind=' spanKindType
spanKindType = 'unspecified' | 'internal' | 'server' | 'client' | 'producer' | 'consumer'
  - The list of spanKinds to include in the metrics selection filter, which are logically 'OR'ed.
  - Optional with default: 'server'
```

## Important Notes

1. **Metrics Computed On-Demand:** With Option 2 (direct OpenSearch querying), metrics are computed dynamically when you query them. There's no pre-aggregation delay.

2. **Trace Indexing:** Traces must be indexed in OpenSearch before metrics can be computed. This typically takes 5-10 seconds.

3. **Trace Context Propagation:** Service dependency edges require both CLIENT and SERVER spans from the same trace (same trace ID) with proper parent-child relationship.

4. **Virtual Nodes:** Uninstrumented services (databases, caches) appear as dependencies in Jaeger UI with appropriate labels.

5. **OTLP Direct:** Applications can send traces directly to Jaeger v2 via OTLP (ports 4317 for gRPC, 4318 for HTTP).

## Building from Source

### Build jaeger-v2 docker image

```shell
cd /root/jaeger
make build
```

### Bring up the dev environment

```bash
docker-compose -f docker-compose-opensearch.yml up
```

## Sending Traces

We will use [tracegen](https://github.com/jaegertracing/jaeger/tree/main/cmd/tracegen)
to emit traces directly to Jaeger v2.

Start the local stack needed for SPM, if not already done:

```shell
docker-compose -f docker-compose-opensearch.yml up
```

Generate a specific number of traces with:

```shell
docker run --env OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://jaeger:4318/v1/traces" \
  --network monitor_backend \
  --rm \
  jaegertracing/jaeger-tracegen:latest \
    -trace-exporter otlp-http \
    -traces 1
```

Or, emit traces over a period of time with:

```shell
docker run --env OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://jaeger:4318/v1/traces" \
  --network monitor_backend \
  --rm \
  jaegertracing/jaeger-tracegen:latest \
    -trace-exporter otlp-http \
    -duration 5s
```

Navigate to Jaeger UI at http://localhost:16686/ and you should be able to see traces from this demo application
under the `tracegen` service.

Then navigate to the Monitor tab at http://localhost:16686/monitor to view the RED metrics.

## Additional Resources

[Service Performance Monitoring (SPM)](https://www.jaegertracing.io/docs/latest/spm/)

[Jaeger HTTP API](https://www.jaegertracing.io/docs/latest/apis/)

[Jaeger v2 Architecture](https://www.jaegertracing.io/docs/latest/architecture/)
