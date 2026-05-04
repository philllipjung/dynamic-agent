#!/bin/bash
# Test all 8 MCP tools
# Updated: 2026-05-03 - Uses mcp_lib.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/mcp_lib.sh"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║         Testing All 8 MCP Tools (2026-05-03)                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Test counter
PASS=0
FAIL=0

# Test a tool
test_tool() {
    local tool_name="$1"
    local arguments="$2"
    local expected_field="$3"
    local tool_num="$4"

    echo "${tool_num}️⃣  $tool_name"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    local result=$(mcp_call "$tool_name" "$arguments")
    local parsed=$(mcp_parse_result "$result")

    if mcp_has_error "$parsed"; then
        mcp_error "FAILED: $(mcp_get_error "$parsed")"
        FAIL=$((FAIL + 1))
    else
        # Show result summary
        if [ -n "$expected_field" ]; then
            echo "$parsed" | jq -r "$expected_field" 2>/dev/null || echo "$parsed" | jq -C . 2>/dev/null | head -10
        else
            echo "$parsed" | jq -C . 2>/dev/null | head -10
        fi
        mcp_success "PASSED"
        PASS=$((PASS + 1))
    fi
    echo ""
}

# 1. Health
test_tool "health" "{}" '".server + " | " + .status + " | " + .version' "1"

# 2. Get Services
test_tool "get_services" "{}" '"Services: " + (.services | length | tostring)' "2"

# 3. Get Span Names
test_tool "get_span_names" '{"service_name":"frontend"}' '"Span names: " + (.span_names | length | tostring)' "3"

# 4. Search Traces
echo "4️⃣  search_traces"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
local result=$(mcp_call "search_traces" '{"service_name":"frontend"}')
local parsed=$(mcp_parse_result "$result")

if mcp_has_error "$parsed"; then
    mcp_error "FAILED: $(mcp_get_error "$parsed")"
    FAIL=$((FAIL + 1))
else
    TRACE_COUNT=$(echo "$parsed" | jq -r '.traces | length' 2>/dev/null)
    echo "Found $TRACE_COUNT traces"
    echo "$parsed" | jq -r '.traces[:3][]?.trace_id' 2>/dev/null | head -3
    mcp_success "PASSED"
    PASS=$((PASS + 1))
fi
echo ""

# Get first trace ID for remaining tests
TRACE_ID=$(echo "$parsed" | jq -r '.traces[0]?.trace_id' 2>/dev/null)

if [ -z "$TRACE_ID" ] || [ "$TRACE_ID" = "null" ]; then
    mcp_warning "No trace ID found, skipping remaining tests"
else
    mcp_info "Using trace: $TRACE_ID"
    echo ""

    # 5. Get Trace Topology
    test_tool "get_trace_topology" "{\"trace_id\":\"$TRACE_ID\"}" '"Root: " + .root_span.service + ":" + .root_span.span_name' "5"

    # 6. Get Trace Errors
    echo "6️⃣  get_trace_errors"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    local result=$(mcp_call "get_trace_errors" "{\"trace_id\":\"$TRACE_ID\"}")
    local parsed=$(mcp_parse_result "$result")

    if mcp_has_error "$parsed"; then
        mcp_error "FAILED: $(mcp_get_error "$parsed")"
        FAIL=$((FAIL + 1))
    else
        ERROR_COUNT=$(echo "$parsed" | jq 'length' 2>/dev/null)
        echo "Error spans: $ERROR_COUNT"
        mcp_success "PASSED"
        PASS=$((PASS + 1))
    fi
    echo ""

    # 7. Get Span Details
    echo "7️⃣  get_span_details"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    # Get a span ID from topology
    local topology_result=$(mcp_call "get_trace_topology" "{\"trace_id\":\"$TRACE_ID\"}")
    local SPAN_ID=$(echo "$topology_result" | python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data: ')[1]); sc=d['result']['structuredContent']; print(sc.get('root_span',{}).get('span_id',''))" 2>/dev/null)

    if [ -n "$SPAN_ID" ]; then
        local result=$(mcp_call "get_span_details" "{\"trace_id\":\"$TRACE_ID\",\"span_ids\":[\"$SPAN_ID\"]}")
        local parsed=$(mcp_parse_result "$result")

        if mcp_has_error "$parsed"; then
            mcp_error "FAILED: $(mcp_get_error "$parsed")"
            FAIL=$((FAIL + 1))
        else
            echo "Span details retrieved"
            mcp_success "PASSED"
            PASS=$((PASS + 1))
        fi
    else
        mcp_warning "No span ID available"
        FAIL=$((FAIL + 1))
    fi
    echo ""

    # 8. Get Critical Path
    test_tool "get_critical_path" "{\"trace_id\":\"$TRACE_ID\"}" '"Total duration: " + (.total_duration_us / 1000 | tostring) + "ms"' "8"
fi

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Test Results                                               ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo -e "${GREEN}✅ Passed: $PASS${NC}"
echo -e "${RED}❌ Failed: $FAIL${NC}"
echo ""

if [ $FAIL -eq 0 ]; then
    mcp_success "All tests passed!"
    exit 0
else
    mcp_error "Some tests failed"
    exit 1
fi
