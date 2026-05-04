#!/bin/bash
# MCP Library - Shared functions for all MCP scripts
# Version: 2.0
# Updated: 2026-05-03

# Session management
SESSION_FILE="/tmp/mcp_session_id"
MCP_SERVER="http://localhost:16687/mcp"

# Colors for output
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

# Initialize MCP session
mcp_init_session() {
    if [ -f "$SESSION_FILE" ]; then
        # Verify session is still valid
        local sid=$(cat "$SESSION_FILE")
        local test=$(curl -s -X POST "$MCP_SERVER" \
            -H "Content-Type: application/json" \
            -H "Mcp-Session-Id: $sid" \
            -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"health","arguments":{}}}' 2>&1)

        if ! echo "$test" | grep -q "session not found"; then
            return 0
        fi
    fi

    # Initialize new session
    echo -e "${BLUE}Initializing MCP session...${NC}" >&2

    local sid=$(curl -v -X POST "$MCP_SERVER" \
        -H "Content-Type: application/json" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"clientInfo":{"name":"mcp-scripts","version":"2.0"}}}' 2>&1 | \
        grep "< Mcp-Session-Id:" | sed 's/.*: //' | tr -d '\r')

    if [ -z "$sid" ]; then
        echo -e "${RED}Failed to initialize session${NC}" >&2
        return 1
    fi

    echo "$sid" > "$SESSION_FILE"

    # Send initialized notification
    curl -s -X POST "$MCP_SERVER" \
        -H "Content-Type: application/json" \
        -H "Mcp-Session-Id: $sid" \
        -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null 2>&1

    echo -e "${GREEN}Session initialized: $sid${NC}" >&2
    return 0
}

# Get session ID
mcp_get_session() {
    mcp_init_session || return 1
    cat "$SESSION_FILE"
}

# Call MCP tool
mcp_call() {
    local tool_name="$1"
    local arguments="$2"
    local session_id

    session_id=$(mcp_get_session) || return 1

    local id=$$  # Use PID as request ID
    local payload=$(jq -n \
        --arg jsonrpc "2.0" \
        --argjson id "$id" \
        --arg method "tools/call" \
        --arg name "$tool_name" \
        --argjson args "$arguments" \
        '{jsonrpc: $jsonrpc, id: $id, method: $method, params: {name: $name, arguments: $args}}')

    curl -s -X POST "$MCP_SERVER" \
        -H "Content-Type: application/json" \
        -H "Mcp-Session-Id: $session_id" \
        -d "$payload"
}

# Parse SSE response and extract structured content
mcp_parse_result() {
    local response="$1"

    # Extract JSON from SSE format
    echo "$response" | python3 -c "
import sys, json

try:
    # Split by 'data: ' and get the JSON part
    lines = sys.stdin.read().strip().split('\n')
    for line in lines:
        if line.startswith('data: '):
            data = json.loads(line[6:])
            if 'result' in data and 'structuredContent' in data['result']:
                print(json.dumps(data['result']['structuredContent'], indent=2))
            elif 'error' in data:
                print(json.dumps({'error': data['error']}, indent=2))
            sys.exit(0)
except Exception as e:
    print(f'Parse error: {e}', file=sys.stderr)
    sys.exit(1)
" 2>/dev/null || echo "$response" | head -5
}

# Check if result has error
mcp_has_error() {
    local result="$1"
    echo "$result" | grep -q '"error"'
}

# Extract error message
mcp_get_error() {
    local result="$1"
    echo "$result" | python3 -c "
import sys, json
try:
    data = json.loads(sys.stdin.read())
    if 'error' in data:
        print(data['error'].get('message', 'Unknown error'))
except:
    print('Parse error')
" 2>/dev/null
}

# Pretty print JSON with color
mcp_pretty_json() {
    local json="$1"
    echo "$json" | jq -C . 2>/dev/null || echo "$json"
}

# Print section header
mcp_header() {
    local text="$1"
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo -e "${BLUE}$text${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# Print success message
mcp_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

# Print error message
mcp_error() {
    echo -e "${RED}❌ $1${NC}" >&2
}

# Print warning message
mcp_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}" >&2
}

# Print info message
mcp_info() {
    echo -e "${BLUE}ℹ️  $1${NC}" >&2
}

# Print tool result status
mcp_result_status() {
    local result="$1"
    local tool_name="$2"

    if mcp_has_error "$result"; then
        local error=$(mcp_get_error "$result")
        mcp_error "$tool_name failed: $error"
        return 1
    else
        mcp_success "$tool_name completed"
        return 0
    fi
}
