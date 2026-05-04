# Jaeger MCP Scripts and Documentation

**Last Updated**: 2026-05-03

## Quick Start

```bash
cd /root/mcp

# Check if traces are flowing
./scripts/check-traces.sh

# Restart Jaeger if needed
./scripts/restart-jaeger.sh

# Test MCP tools
./scripts/test_tools.sh
```

## Scripts

| Script | Purpose | Usage |
|--------|---------|-------|
| `check-traces.sh` | Verify entire trace pipeline status | `./scripts/check-traces.sh` |
| `restart-jaeger.sh` | Restart Jaeger with verification | `./scripts/restart-jaeger.sh` |
| `health.sh` | Quick MCP health check | `./scripts/health.sh` |
| `init_session.sh` | Initialize MCP session | `./scripts/init_session.sh` |
| `test_tools.sh` | Test all 8 MCP tools | `./scripts/test_tools.sh` |
| `view_all.sh` | View all tools with formatted output | `./scripts/view_all.sh` |
| `save_results.sh` | Save tool results to JSON | `./scripts/save_results.sh` |
| `show_schemas.py` | View tool parameter schemas | `./scripts/show_schemas.py` |

## Documentation

| File | Description |
|------|-------------|
| `docs/20260503_MCP.md` | Complete MCP testing guide with restart instructions |
| `CHANGELOG.md` | Version history and changes |

## Trace Pipeline Components

```
Microsim (HotROD trace generator)
  ↓ OTLP (port 4318)
Jaeger v2 (OTEL Collector + Extensions)
  ↓
OpenSearch (jaeger-span-YYYY-MM-DD indices)
  ↑
Jaeger UI (http://localhost:5173)
```

## Configuration Files

| File | Purpose |
|------|---------|
| `/root/test/config/jaeger-local.yaml` | Jaeger configuration |
| `/root/test/logs/jaeger-current.log` | Jaeger runtime logs |

## Access URLs

| Service | URL |
|---------|-----|
| Jaeger UI | http://localhost:5173 |
| Jaeger API | http://localhost:16686 |
| Jaeger MCP | http://localhost:16687/mcp |
| OpenSearch | http://localhost:9200 |

## Extensions

Jaeger v2 uses OpenTelemetry Collector extensions:

| Extension | Purpose |
|-----------|---------|
| `jaeger_storage` | Write traces to OpenSearch |
| `jaeger_query` | Provide query API for Jaeger UI |
| `jaeger_mcp` | Model Context Protocol server for AI agents |

## Troubleshooting

### Traces not appearing?

```bash
# Run full pipeline check
./scripts/check-traces.sh

# Check logs
tail -50 /root/test/logs/jaeger-current.log

# Restart Jaeger
./scripts/restart-jaeger.sh
```

### MCP not working?

```bash
# Initialize session
./scripts/init_session.sh

# Health check
./scripts/health.sh

# Test all tools
./scripts/test_tools.sh
```

## Key Learnings

1. **Use `snake_case`** for MCP parameter names (Go convention, not camelCase)
2. **Session initialization required** before calling any MCP tools
3. **Send `notifications/initialized`** after initialize message
4. **SSE format** requires parsing `data:` prefix in responses
5. **Trace IDs expire** - use current traces from search results

## System Status

Current components:
- **Jaeger**: v2.16.0 (OTEL Collector + Jaeger extensions)
- **OpenSearch**: 3.1.0
- **Trace Generator**: Microsim (HotROD demo)
- **Backend**: OpenSearch with date-based indices

For detailed testing procedures and troubleshooting, see `docs/20260503_MCP.md`.
