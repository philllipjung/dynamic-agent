#!/bin/bash
# Initialize MCP session and save session ID to file

SESSION_FILE="/tmp/mcp_session_id"

echo "Initializing MCP session..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Initialize and capture session ID
SESSION_ID=$(curl -v -X POST http://localhost:16687/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"clientInfo":{"name":"mcp-scripts","version":"1.0"}}}' 2>&1 | \
  grep "< Mcp-Session-Id:" | sed 's/.*: //' | tr -d '\r')

if [ -z "$SESSION_ID" ]; then
    echo "❌ Failed to initialize session"
    exit 1
fi

# Save session ID to file
echo "$SESSION_ID" > "$SESSION_FILE"
echo "✅ Session initialized: $SESSION_ID"
echo "💾 Saved to: $SESSION_FILE"

# Send initialized notification
echo ""
echo "Sending initialized notification..."
curl -s -X POST http://localhost:16687/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null

echo "✅ Session ready for tool calls"
echo ""
echo "Use session in scripts:"
echo "  source /root/mcp/scripts/init_session.sh"
echo "  # or"
echo "  SESSION_ID=\$(cat /tmp/mcp_session_id)"
