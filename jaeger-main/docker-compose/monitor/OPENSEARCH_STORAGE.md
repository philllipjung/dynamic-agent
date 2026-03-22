# OpenSearch Storage Configuration

This document describes the OpenSearch storage configuration for Jaeger v2 with Service Performance Monitoring (SPM).

## Overview

OpenSearch serves as the primary storage backend for:
- **Traces:** Distributed trace data from applications
- **Metrics:** SPM metrics computed on-demand from trace data

### Architecture

```
┌─────────────┐     OTLP      ┌─────────┐     ┌─────────────┐
│  Apps       │ ────────────> │ Jaeger  │ ──> │ OpenSearch  │
│ (server1/2) │               │ (v2)    │     │             │
└─────────────┘               └─────────┘     └─────────────┘
                                      ^              |
                                      |              v
                              ┌─────────────┐   ┌──────────────┐
                              │ Jaeger UI   │   │ OpenSearch   │
                              │             │   │ Dashboards   │
                              └─────────────┘   └──────────────┘
```

## OpenSearch Configuration

### Docker Service

**File:** `docker-compose-opensearch.yml`

```yaml
opensearch:
  image: opensearchproject/opensearch:3.5.0
  environment:
    - discovery.type=single-node
    - plugins.security.disabled=true
    - http.host=0.0.0.0
    - transport.host=127.0.0.1
    - OPENSEARCH_INITIAL_ADMIN_PASSWORD=passRT%^#234
  ports:
    - "9200:9200"
  healthcheck:
    test: ["CMD-SHELL", "curl -f http://localhost:9200 || exit 1"]
    interval: 10s
    timeout: 10s
    retries: 30
```

### Key Settings

| Setting | Value | Purpose |
|---------|-------|---------|
| `discovery.type` | `single-node` | Single-node cluster (dev/testing) |
| `plugins.security.disabled` | `true` | Disable authentication |
| `http.host` | `0.0.0.0` | Listen on all interfaces |
| `transport.host` | `127.0.0.1` | Bind transport to localhost |
| `OPENSEARCH_INITIAL_ADMIN_PASSWORD` | `passRT%^#234` | Admin password |

### Production Considerations

For production deployments:
- Enable security: Set `plugins.security.disabled=false`
- Use proper password management (secrets, vaults)
- Configure multi-node cluster with `discovery.seed_hosts`
- Set appropriate `heap.size` (typically 50% of available RAM)
- Enable TLS/SSL for transport and HTTP

## Jaeger Storage Configuration

### Trace Storage

**File:** `config-spm-opensearch.yaml`

```yaml
extensions:
  jaeger_storage:
    backends:
      opensearch_trace_storage:
        opensearch:
          server_urls:
            - http://opensearch:9200
```

### Metrics Storage

```yaml
extensions:
  jaeger_storage:
    metric_backends:
      opensearch_metrics_storage:
        opensearch:
          server_urls:
            - http://opensearch:9200
```

### Query Storage

```yaml
extensions:
  jaeger_query:
    storage:
      traces: opensearch_trace_storage
      metrics: opensearch_metrics_storage
```

## Index Patterns

Jaeger creates the following indices in OpenSearch:

### Trace Indices

| Index Pattern | Purpose | Retention |
|---------------|---------|-----------|
| `jaeger-span-*` | Individual span data | Depends on rotation |
| `jaeger-service-*` | Service operation metadata | Permanent |

### Index Rotation

Indices are rotated daily by default:
- `jaeger-span-YYYY-MM-DD`
- `jaeger-service-YYYY-MM-DD`

**Example:**
```
jaeger-span-2026-02-18
jaeger-span-2026-02-17
jaeger-span-2026-02-16
```

### Index Mappings

Jaeger uses Elasticsearch 7.x compatible mappings for OpenSearch 3.x:

```json
{
  "properties": {
    "traceID": {"type": "keyword"},
    "spanID": {"type": "keyword"},
    "operationName": {"type": "keyword"},
    "serviceName": {"type": "keyword"},
    "startTime": {"type": "long"},
    "duration": {"type": "long"},
    "tags": {"type": "object"},
    "logs": {"type": "object"},
    "references": {"type": "object"}
  }
}
```

## Accessing OpenSearch

### Direct API Access

**Check cluster health:**
```bash
curl http://localhost:9200/_cluster/health?pretty
```

**List Jaeger indices:**
```bash
curl http://localhost:9200/_cat/indices?v | grep jaeger
```

**Search traces:**
```bash
curl -X GET "http://localhost:9200/jaeger-span-*/_search?pretty" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "match_all": {}
    },
    "size": 10
  }'
```

**Get service list:**
```bash
curl -X GET "http://localhost:9200/jaeger-service-*/_search?pretty" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "match_all": {}
    }
  }'
```

### OpenSearch Dashboards

**URL:** http://localhost:5601

**Setup Index Patterns:**
1. Navigate to **Management** → **Stack Management** → **Index Patterns**
2. Create index pattern: `jaeger-span-*`
3. Create index pattern: `jaeger-service-*`
4. Select `@timestamp` as time field

**Sample Queries:**

```json
// All spans for a service
{
  "query": {
    "term": {
      "process.serviceName.keyword": "server1"
    }
  }
}

// Slow traces (>1 second)
{
  "query": {
    "range": {
      "duration": {
        "gte": 1000000
      }
    }
  }
}

// Error traces
{
  "query": {
    "match": {
      "tags.key": "error"
    }
  }
}
```

## Performance Tuning

### OpenSearch Heap Size

Default heap size is 50% of container memory. Adjust in `docker-compose.yml`:

```yaml
opensearch:
  environment:
    - OPENSEARCH_JAVA_OPTS=-Xms2g -Xmx2g
  deploy:
    resources:
      limits:
        memory: 4G
```

### Refresh Interval

Control how often indices are refreshed (made searchable):

```yaml
# In Jaeger config
opensearch:
  server_urls:
    - http://opensearch:9200
  refresh_interval: 30s  # Default: 1s
```

**Trade-offs:**
- **Lower (1s):** Near real-time search, higher CPU/load
- **Higher (60s):** Better indexing performance, delayed visibility

### Number of Shards

Configure for your data volume:

```yaml
opensearch:
  num_shards: 3  # Default: 5
  num_replicas: 1  # Default: 1
```

**Guidelines:**
- Small deployments: 1-3 shards
- Medium deployments: 3-5 shards
- Large deployments: 10+ shards, distribute across nodes

### Index Lifecycle Management

Configure automatic index cleanup:

```json
PUT _ilm/policy/jaeger_policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_age": "1d",
            "max_size": "50gb"
          }
        }
      },
      "delete": {
        "min_age": "7d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

## Metrics Storage (SPM)

### On-Demand Computation

With Option 2 architecture, metrics are computed from traces when queried:

```bash
# API endpoint for metrics
curl "http://localhost:16686/api/metrics/calls?service=server1"
curl "http://localhost:16686/api/metrics/latencies?service=server1&quantile=0.95"
curl "http://localhost:16686/api/metrics/errors?service=server1"
```

### Metrics Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `service` | string | Service name | Required |
| `operation` | string | Operation name | All operations |
| `quantile` | float | Latency percentile (0-1) | 0.95 |
| `groupByOperation` | boolean | Group by operation | false |
| `endTs` | long | End timestamp (ms) | now |
| `lookback` | long | Lookback period (ms) | 3600000 (1h) |
| `step` | long | Step between points (ms) | 5000 (5s) |

### Performance Considerations

**Metrics computation load:**
- Depends on trace volume in time range
- Larger lookback = more data to scan
- Complex queries = longer computation time

**Optimization tips:**
1. Use appropriate time ranges (avoid 24h+)
2. Specify `groupByOperation=true` to reduce aggregation
3. Use `step` parameter to control granularity

## Troubleshooting

### Issue: Indices Not Created

**Symptoms:**
- Jaeger logs show "index not found"
- No `jaeger-*` indices in OpenSearch

**Solution:**
```bash
# Check Jaeger logs
docker logs monitor_jaeger_1 | grep -i "index"

# Manually create index template
curl -X PUT "http://localhost:9200/_template/jaeger-span" \
  -H 'Content-Type: application/json' \
  -d @jaeger-span-mapping.json
```

### Issue: High Memory Usage

**Symptoms:**
- OpenSearch container OOM killed
- Slow query responses

**Solution:**
```yaml
# Increase memory limit
opensearch:
  deploy:
    resources:
      limits:
        memory: 8G
      reservations:
        memory: 4G
  environment:
    - OPENSEARCH_JAVA_OPTS=-Xms4g -Xmx4g
```

### Issue: Slow Trace Searches

**Symptoms:**
- Jaeger UI searches timeout
- OpenSearch queries slow

**Solutions:**

1. **Reduce time range:**
   - Search last 1-2 hours instead of 24h

2. **Add filters:**
   - Filter by service, operation, tags

3. **Check cluster health:**
   ```bash
   curl http://localhost:9200/_cluster/health?pretty
   ```

4. **Optimize indices:**
   ```bash
   curl -X POST "http://localhost:9200/jaeger-span-*/_forcemerge?max_num_segments=1"
   ```

### Issue: Metrics Show NaN

**Symptoms:**
- SPM API returns `"doubleValue": "NaN"`

**Causes:**
- Insufficient trace data in time range
- No matching traces for query

**Solutions:**
1. Generate more traces
2. Increase lookback period
3. Verify traces are being indexed:
   ```bash
   curl http://localhost:9200/_cat/indices?v | grep jaeger-span
   ```

## Backup and Recovery

### Snapshot Repository

Configure snapshot repository for backups:

```json
PUT _snapshot/backup_repo
{
  "type": "fs",
  "settings": {
    "location": "/mnt/backup"
  }
}
```

### Create Snapshot

```bash
curl -X PUT "http://localhost:9200/_snapshot/backup_repo/snapshot_1?wait_for_completion=true"
```

### Restore Snapshot

```bash
curl -X POST "http://localhost:9200/_snapshot/backup_repo/snapshot_1/_restore"
```

## Migration from Elasticsearch

### Compatibility

- **OpenSearch 3.x** uses Elasticsearch 7.x compatible APIs
- **Jaeger v2** automatically detects OpenSearch 3.x
- Uses ES 7.x index mappings

### Migration Steps

1. **Export data from Elasticsearch:**
   ```bash
   curl -X POST "http://elasticsearch:9200/_snapshot/backup/snap_1"
   ```

2. **Import to OpenSearch:**
   ```bash
   curl -X POST "http://opensearch:9200/_snapshot/backup/snap_1/_restore"
   ```

3. **Update Jaeger configuration:**
   ```yaml
   server_urls:
     - http://opensearch:9200  # Changed from elasticsearch
   ```

## Reference Links

- [OpenSearch Documentation](https://opensearch.org/docs/)
- [Jaeger Storage Documentation](https://www.jaegertracing.io/docs/latest/deployment/#storage)
- [Jaeger SPM Architecture](https://www.jaegertracing.io/docs/latest/spm/)
- [OpenSearch Index Management](https://opensearch.org/docs/latest/im-plugin/index-management/)
