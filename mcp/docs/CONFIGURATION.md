# Jaeger Configuration Guide

## Quick Answer

**Only 1 file needs configuration**: `/root/test/config/jaeger-local.yaml`

---

## Required Configuration

### File: `/root/test/config/jaeger-local.yaml`

#### 1. OpenSearch Connection

```yaml
extensions:
  jaeger_storage:
    backends:
      opensearch_trace_storage:
        opensearch:
          server_urls: ["http://localhost:9200"]  # ← CHANGE THIS
```

**When to change**: OpenSearch running on different host/port

#### 2. OTLP Receiver Ports

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"  # ← gRPC port
      http:
        endpoint: "0.0.0.0:4318"  # ← HTTP port
```

**When to change**: Your app sends to different port

#### 3. MCP Server Port

```yaml
extensions:
  jaeger_mcp:
    endpoint: "0.0.0.0:16687"  # ← MCP server port
```

**When to change**: Port conflict or need different port

---

## Optional Configuration

### File: `/opt/opensearch/config/opensearch.yml`

**CORS settings** - only needed if Jaeger UI can't connect to OpenSearch:

```yaml
http.cors.enabled: true
http.cors.allow-origin: "*"
http.cors.allow-methods: "OPTIONS, HEAD, GET, POST, PUT, DELETE"
http.cors.allow-headers: "X-Requested-With, Content-Type, Content-Length, Authorization"
```

---

## Apply Configuration Changes

```bash
# Restart Jaeger
cd /root/mcp
./scripts/restart-jaeger.sh

# Verify
./scripts/check-traces.sh
```

---

## Complete Example Config

```yaml
service:
  extensions: [jaeger_storage, jaeger_query, jaeger_mcp]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [jaeger_storage_exporter]

extensions:
  jaeger_query:
    storage:
      traces: opensearch_trace_storage

  jaeger_storage:
    backends:
      opensearch_trace_storage:
        opensearch:
          server_urls: ["http://localhost:9200"]
    metric_backends:
      opensearch_metrics_storage:
        opensearch:
          server_urls: ["http://localhost:9200"]

  jaeger_mcp:
    endpoint: "0.0.0.0:16687"

receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"
      http:
        endpoint: "0.0.0.0:4318"

processors:
  batch:

exporters:
  jaeger_storage_exporter:
    trace_storage: opensearch_trace_storage
```

---

## Common Scenarios

### Scenario 1: OpenSearch on Remote Host

```yaml
server_urls: ["http://opensearch.example.com:9200"]
```

### Scenario 2: Use Port 9418 for OTLP

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: "0.0.0.0:9418"
```

### Scenario 3: Enable TLS/SSL

```yaml
opensearch:
  server_urls: ["https://localhost:9200"]
  tls:
    ca: /path/to/ca.crt
    cert: /path/to/client.crt
    key: /path/to/client.key
```

---

## Troubleshooting

### Problem: Traces not appearing

1. Check Jaeger config:
   ```bash
   cat /root/test/config/jaeger-local.yaml | grep server_urls
   ```

2. Verify OpenSearch is reachable:
   ```bash
   curl http://localhost:9200/_cluster/health
   ```

3. Check Jaeger logs:
   ```bash
   tail -50 /root/test/logs/jaeger-current.log
   ```

### Problem: Port already in use

```bash
# Find what's using the port
ss -tlnp | grep :4318

# Change port in config and restart
```

---

## Summary

| File | Required? | What to Configure |
|------|-----------|-------------------|
| `/root/test/config/jaeger-local.yaml` | ✅ YES | OpenSearch URL, ports |
| `/opt/opensearch/config/opensearch.yml` | ⚠️ Maybe | CORS (if UI issues) |
| Other files | ❌ NO | Use defaults |

**That's it!** Only one file needs configuration in most cases.
