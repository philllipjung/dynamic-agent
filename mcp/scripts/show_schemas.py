#!/usr/bin/env python3
import json
import subprocess

# Get the MCP response
result = subprocess.run([
    "curl", "-s", "-X", "POST", "http://localhost:16687/mcp",
    "-H", "Content-Type: application/json",
    "-H", "Mcp-Session-Id: WXLKKAVZA2NWWM4ILOMMN54CEN",
    "-d", '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
], capture_output=True, text=True)

# Parse SSE format - extract data after "data: "
for line in result.stdout.strip().split('\n'):
    if line.startswith('data: '):
        data = json.loads(line[6:])  # Remove "data: " prefix
        if 'result' in data and 'tools' in data['result']:
            tools = data['result']['tools']

            print("=" * 60)
            print("MCP TOOLS - PARAMETER ANALYSIS")
            print("=" * 60)
            print()

            # Analyze each tool
            for tool in tools:
                name = tool.get('name', 'unknown')
                schema = tool.get('inputSchema', {})
                props = schema.get('properties', {})
                required = schema.get('required', [])

                print(f"Tool: {name}")
                print(f"  Available parameters: {list(props.keys())}")
                print(f"  Required parameters: {required}")
                if props:
                    print(f"  Parameter details:")
                    for param_name, param_info in props.items():
                        param_type = param_info.get('type', 'unknown')
                        param_desc = param_info.get('description', '')
                        print(f"    - {param_name} ({param_type}): {param_desc}")
                print()
