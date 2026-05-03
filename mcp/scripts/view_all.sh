#!/bin/bash
# MCP Comprehensive Viewer - Shows all tools with formatted output
# Updated: 2026-05-03 - Uses mcp_lib.sh for session management

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/mcp_lib.sh"

# Parse MCP result using library
parse_and_show() {
    local tool_name="$1"
    local arguments="$2"
    local extract_fn="$3"

    mcp_header "$tool_name"
    local result=$(mcp_call "$tool_name" "$arguments")
    local parsed=$(mcp_parse_result "$result")

    if mcp_has_error "$parsed"; then
        mcp_error "Failed: $(mcp_get_error "$parsed")"
        return 1
    fi

    if [ -n "$extract_fn" ]; then
        echo "$parsed" | $extract_fn
    else
        echo "$parsed" | jq -C . 2>/dev/null || echo "$parsed"
    fi

    mcp_result_status "$result" "$tool_name"
    echo ""
}

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║              MCP Comprehensive Viewer v2.0                    ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# 1. Health Check
parse_and_show "Health Check" "{}" 'jq -r '"'"'["Server: \(.server)", "Status: \(.status)", "Version: \(.version)"][]'"'"' 2>/dev/null'

# 2. Get Services
parse_and_show "Services List" "{}" 'jq -r ".services[]?" 2>/dev/null'

# 3. Get Span Names
parse_and_show "Span Names (frontend)" '{"service_name":"frontend"}' 'jq -r ".span_names[]?.name" 2>/dev/null'

# 4. Search Traces
parse_and_show "Recent Traces (frontend)" '{"service_name":"frontend"}' 'jq -r ".traces[:3][] | "\(.trace_id) (\(.span_count) spans)"' 2>/dev/null'

# 5. Get Trace Topology (with auto trace ID detection)
mcp_header "Trace Topology (latest trace)"
TRACE_ID=$(mcp_call "search_traces" '{"service_name":"frontend"}' | \
    python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data: ')[1]); print(d['result']['structuredContent']['traces'][0]['trace_id'])" 2>/dev/null)

if [ -n "$TRACE_ID" ]; then
    echo "Trace ID: $TRACE_ID"
    local result=$(mcp_call "get_trace_topology" "{\"trace_id\":\"$TRACE_ID\"}")
    local parsed=$(mcp_parse_result "$result")

    if ! mcp_has_error "$parsed"; then
        echo "$parsed" | jq -r '"Root: \(.root_span.service):\(.root_span.span_name) (\(.root_span.duration_us / 1000 | tostring + "ms"))"' 2>/dev/null
        echo "Duration: $(echo "$parsed" | jq -r '.root_span.duration_us / 1000 | tostring + " ms"' 2>/dev/null)"
        mcp_success "Topology loaded"
    else
        mcp_error "Failed to load topology"
    fi
else
    mcp_warning "No traces found"
fi
echo ""

# 6. Get Trace Errors
if [ -n "$TRACE_ID" ]; then
    parse_and_show "Trace Errors" "{\"trace_id\":\"$TRACE_ID\"}" 'jq -r "if length == 0 then \"No errors\" else \"\(.[] | \"\(.service):\(.span_name) - \(.status)\")\" end" 2>/dev/null'
fi

# 7. Get Critical Path
if [ -n "$TRACE_ID" ]; then
    parse_and_show "Critical Path" "{\"trace_id\":\"$TRACE_ID\"}" 'jq -r "\"Total duration: \(.total_duration_us / 1000 | tostring + \"ms\")\nSegments: \(.segments | length | tostring)\"" 2>/dev/null'
fi

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  ✅ All Results Displayed!                                   ║"
echo "╚══════════════════════════════════════════════════════════════╝"
