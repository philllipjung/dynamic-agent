#!/bin/bash
# Quick health check with dynamic session management

SESSION_FILE="/tmp/mcp_session_id"

# Check if session exists, if not initialize it
if [ ! -f "$SESSION_FILE" ]; then
    /root/mcp/scripts/init_session.sh > /dev/null 2>&1
fi

SID=$(cat "$SESSION_FILE" 2>/dev/null)

if [ -z "$SID" ]; then
    echo "❌ Failed to load session ID"
    exit 1
fi

# Parse and display health status
RESULT=$(curl -s -X POST http://localhost:16687/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"health","arguments":{}}}')

echo "$RESULT" | python3 -c "
import sys, json
try:
    data = json.loads(sys.stdin.read().split('data: ')[1])
    sc = data['result']['structuredContent']
    print(f'Server: {sc[\"server\"]}')
    print(f'Status: {sc[\"status\"]}')
    print(f'Version: {sc[\"version\"]}')
except:
    print('Parse error, raw output:')
    print(sys.stdin.read()[:200])
" 2>/dev/null || echo "$RESULT" | head -3
