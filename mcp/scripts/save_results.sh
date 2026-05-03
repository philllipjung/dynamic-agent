#!/bin/bash
# Save MCP results to file for analysis
# Updated: 2026-05-03 - Uses mcp_lib.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/mcp_lib.sh"

OUTPUT_DIR="/root/mcp/results"
mkdir -p "$OUTPUT_DIR"

mcp_info "Saving MCP results to $OUTPUT_DIR"
echo ""

# Helper to save result
save_result() {
    local tool_name="$1"
    local arguments="$2"
    local filename="$3"

    local result=$(mcp_call "$tool_name" "$arguments")
    echo "$result" > "$OUTPUT_DIR/$filename"

    if mcp_has_error "$result"; then
        mcp_error "$filename: $(mcp_get_error "$result")"
        return 1
    else
        mcp_success "$filename"
        return 0
    fi
}

# 1. Health
save_result "health" "{}" "health.json"

# 2. Services
save_result "get_services" "{}" "services.json"

# 3. Get a valid trace ID
mcp_info "Searching for valid trace..."
search_result=$(mcp_call "search_traces" '{"service_name":"frontend"}')
TRACE_ID=$(echo "$search_result" | python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data: ')[1]); sc=d['result']['structuredContent']; traces=sc.get('traces', []); print(traces[0]['trace_id'] if traces else '')" 2>/dev/null)

if [ -n "$TRACE_ID" ]; then
    mcp_success "Using trace: $TRACE_ID"

    # 4. Trace topology
    save_result "get_trace_topology" "{\"trace_id\":\"$TRACE_ID\"}" "topology.json"

    # 5. Trace errors
    save_result "get_trace_errors" "{\"trace_id\":\"$TRACE_ID\"}" "errors.json"

    # 6. Critical path
    save_result "get_critical_path" "{\"trace_id\":\"$TRACE_ID\"}" "critical_path.json"
else
    mcp_warning "No traces found, skipping topology/errors/critical_path"
fi

echo ""
echo "Files saved:"
ls -lh "$OUTPUT_DIR" | grep -v "^total" | grep -v "^d" | awk '{print "  " $9 " (" $5 ")"}'
echo ""
echo "View files with:"
echo "  cat $OUTPUT_DIR/health.json | jq ."
echo "  cat $OUTPUT_DIR/services.json | jq ."
echo "  cat $OUTPUT_DIR/topology.json | jq . | less"
