#!/bin/bash
# MCP Main Entry Point
# Usage: ./mcp.sh [command] [options]
# Updated: 2026-05-03

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/mcp_lib.sh"

# Show usage
show_usage() {
    cat << EOF
╔══════════════════════════════════════════════════════════════╗
║                    Jaeger MCP Tools v2.0                     ║
╚══════════════════════════════════════════════════════════════╝

Usage: ./mcp.sh [command] [options]

Commands:
  init                    Initialize MCP session
  health                  Quick health check
  view [tool]             View results from a tool
  test [tool]             Test MCP tools
  save                    Save results to files
  schema                  Show tool parameter schemas
  help                    Show this help message

View Options:
  all                     View all tools (default)
  health                  Server health status
  services                List all services
  spans [service]         Span names for a service
  traces [service]        Search traces
  topology [trace_id]     Trace topology tree
  errors [trace_id]       Find error spans
  span [trace_id]         Span details
  critical [trace_id]     Critical path analysis

Test Options:
  all                     Test all 8 tools (default)
  quick                   Quick test (first 3 tools)

Examples:
  ./mcp.sh init                    # Initialize session
  ./mcp.sh health                  # Check server health
  ./mcp.sh view all                # View all tools
  ./mcp.sh view traces frontend    # Search traces for frontend
  ./mcp.sh view topology abc123    # View trace topology
  ./mcp.sh test all                # Test all tools
  ./mcp.sh save                    # Save results to files

For more details, see: README.md
EOF
}

# Initialize session
cmd_init() {
    mcp_init_session
    local sid=$(cat "$SESSION_FILE")
    mcp_success "Session ready: $sid"
    echo ""
    echo "Use session in your scripts:"
    echo "  source /root/mcp/lib/mcp_lib.sh"
    echo "  mcp_call health '{}'"
}

# Health check
cmd_health() {
    mcp_header "Health Check"
    local result=$(mcp_call "health" "{}")
    local parsed=$(mcp_parse_result "$result")

    if mcp_has_error "$parsed"; then
        mcp_error "Health check failed"
        echo "$parsed" | jq -r '.error.message' 2>/dev/null
        return 1
    fi

    echo "$parsed" | jq -r '"Server: \(.server)\nStatus: \(.status)\nVersion: \(.version)"' 2>/dev/null
    mcp_success "Server is healthy"
}

# View tool results
cmd_view() {
    local tool="${1:-all}"

    case "$tool" in
        health)
            mcp_header "Server Health"
            local result=$(mcp_call "health" "{}")
            mcp_parse_result "$result" | jq -C .
            mcp_result_status "$result" "Health check"
            ;;
        services)
            mcp_header "Services List"
            local result=$(mcp_call "get_services" "{}")
            mcp_parse_result "$result" | jq -C '.services[]' 2>/dev/null
            mcp_result_status "$result" "Get services"
            ;;
        spans)
            local service="${2:-frontend}"
            mcp_header "Span Names for: $service"
            local result=$(mcp_call "get_span_names" "{\"service_name\":\"$service\"}")
            mcp_parse_result "$result" | jq -C '.span_names[]?.name' 2>/dev/null
            mcp_result_status "$result" "Get span names"
            ;;
        traces)
            local service="${2:-frontend}"
            mcp_header "Trace Search for: $service"
            local result=$(mcp_call "search_traces" "{\"service_name\":\"$service\"}")
            mcp_parse_result "$result" | jq -C '.traces[] | {trace_id, span_count}' 2>/dev/null
            mcp_result_status "$result" "Search traces"
            ;;
        topology)
            local trace_id="${2:-$1}"
            if [ "$trace_id" = "topology" ]; then
                # Get latest trace if not provided
                trace_id=$(mcp_call "search_traces" "{\"service_name\":\"frontend\"}" | \
                    python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data: ')[1]); print(d['result']['structuredContent']['traces'][0]['trace_id'])" 2>/dev/null)
            fi
            mcp_header "Trace Topology: $trace_id"
            local result=$(mcp_call "get_trace_topology" "{\"trace_id\":\"$trace_id\"}")
            mcp_parse_result "$result" | jq -C '.root_span | {service, span_name, duration_us}' 2>/dev/null
            mcp_result_status "$result" "Get trace topology"
            ;;
        errors)
            local trace_id="${2:-$1}"
            if [ "$trace_id" = "errors" ]; then
                trace_id=$(mcp_call "search_traces" "{\"service_name\":\"frontend\"}" | \
                    python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data: ')[1]); print(d['result']['structuredContent']['traces'][0]['trace_id'])" 2>/dev/null)
            fi
            mcp_header "Trace Errors: $trace_id"
            local result=$(mcp_call "get_trace_errors" "{\"trace_id\":\"$trace_id\"}")
            mcp_parse_result "$result" | jq -C '.[] | {service, span_name, status}' 2>/dev/null
            mcp_result_status "$result" "Get trace errors"
            ;;
        span|critical)
            mcp_error "Use 'view topology' or 'view errors' instead"
            return 1
            ;;
        all)
            "$SCRIPT_DIR/scripts/view_all.sh"
            ;;
        *)
            mcp_error "Unknown view option: $tool"
            echo "Run './mcp.sh help' for usage"
            return 1
            ;;
    esac
}

# Test tools
cmd_test() {
    local mode="${1:-all}"

    case "$mode" in
        quick)
            mcp_info "Running quick test (first 3 tools)..."
            "$SCRIPT_DIR/scripts/test_tools.sh" 2>&1 | head -50
            ;;
        all)
            mcp_info "Running full test (all 8 tools)..."
            "$SCRIPT_DIR/scripts/test_tools.sh"
            ;;
        *)
            mcp_error "Unknown test mode: $mode"
            echo "Available: quick, all"
            return 1
            ;;
    esac
}

# Save results
cmd_save() {
    mcp_info "Saving results to /root/mcp/results/..."
    "$SCRIPT_DIR/scripts/save_results.sh"
}

# Show schemas
cmd_schema() {
    mcp_info "Loading tool schemas..."
    python3 "$SCRIPT_DIR/scripts/show_schemas.py"
}

# Main
main() {
    local command="${1:-help}"
    shift || true

    case "$command" in
        init)
            cmd_init "$@"
            ;;
        health)
            cmd_health "$@"
            ;;
        view)
            cmd_view "$@"
            ;;
        test)
            cmd_test "$@"
            ;;
        save)
            cmd_save "$@"
            ;;
        schema)
            cmd_schema "$@"
            ;;
        help|--help|-h)
            show_usage
            ;;
        *)
            mcp_error "Unknown command: $command"
            echo ""
            show_usage
            exit 1
            ;;
    esac
}

main "$@"
