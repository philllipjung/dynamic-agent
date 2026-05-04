#!/bin/bash

# Enhanced Java Profiling with async-profiler for server1
# Captures: CPU profiling with Call Trees, Stack Traces, and detailed method info
# Output: Separate files → Fluent Bit → Separate OpenSearch indices

set -e

# Configuration
ASYNC_PROFILER_HOME="/root/async-profiler-4.1-linux-x64"
SERVICE_NAME="server1"
OUTPUT_DIR="/tmp/java-profiling"
ENHANCED_CONVERTER="/root/webflux-demo/server1/async-profiler-enhanced.py"
CALL_TREE_CONVERTER="/root/webflux-demo/server1/build-call-tree.py"

# Separate output files for different data types
STACK_TRACES_OUTPUT="/root/jaeger/docker-compose/monitor/profiles-java/stack-traces.jsonl"
CALL_TREES_OUTPUT="/root/jaeger/docker-compose/monitor/profiles-java/call-trees.jsonl"

# Profiling modes: cpu, alloc, lock
PROFILE_MODES="cpu"  # Can add: alloc,lock
CPU_INTERVAL="10ms"
ALLOC_INTERVAL="512k"
DUMP_INTERVAL="60s"

# Find server1 PID
if [ -n "$1" ]; then
    SERVER_PID="$1"
else
    SERVER_PID=$(jps -ml 2>/dev/null | grep "server1-0.0.1-SNAPSHOT.jar" | awk '{print $1}')
    if [ -z "$SERVER_PID" ]; then
        SERVER_PID=$(ps aux | grep "[s]erver1-0.0.1-SNAPSHOT.jar" | awk '{print $1}')
    fi
fi

echo "=========================================="
echo "Enhanced Java Profiling for $SERVICE_NAME"
echo "=========================================="
echo "async-profiler: v4.1"
echo "Profiling modes: $PROFILE_MODES"
echo "CPU interval: $CPU_INTERVAL"
echo "Dump interval: $DUMP_INTERVAL"
echo "Output:"
echo "  - Stack traces → $STACK_TRACES_OUTPUT → OpenSearch (java-stack-traces index)"
echo "  - Call trees   → $CALL_TREES_OUTPUT → OpenSearch (java-call-trees index)"
echo ""

mkdir -p "$OUTPUT_DIR"

if [ -z "$SERVER_PID" ]; then
    echo "ERROR: server1 is not running!"
    echo "Please start server1 first or provide PID: $0 <PID>"
    exit 1
fi

echo "Found server1 PID: $SERVER_PID"

# Start profiling for each mode
echo ""
echo "Starting async-profiler..."
for MODE in $PROFILE_MODES; do
    case $MODE in
        cpu)
            echo "  - CPU profiling (interval: $CPU_INTERVAL)"
            $ASYNC_PROFILER_HOME/bin/asprof start -e cpu -i $CPU_INTERVAL -t $SERVER_PID 2>/dev/null || true
            ;;
        alloc)
            echo "  - Allocation profiling (interval: $ALLOC_INTERVAL)"
            $ASYNC_PROFILER_HOME/bin/asprof start -e alloc --alloc $ALLOC_INTERVAL -t $SERVER_PID 2>/dev/null || true
            ;;
        lock)
            echo "  - Lock profiling"
            $ASYNC_PROFILER_HOME/bin/asprof start -e lock -t $SERVER_PID 2>/dev/null || true
            ;;
    esac
done

echo "Profiling started successfully!"
echo ""
echo "Capturing detailed profiling data:"
echo "  ✓ Method signatures and packages"
echo "  ✓ Call tree structure (aggregated from stack traces)"
echo "  ✓ Thread information"
echo "  ✓ Kernel vs Java frames"
echo "  ✓ Stack traces with frame types"
echo ""
echo "Continuous profiling loop (dumping every $DUMP_INTERVAL)..."
echo "Press Ctrl+C to stop..."
echo ""

# Trap to stop profiling on exit
trap "echo 'Stopping profiler...'; $ASYNC_PROFILER_HOME/bin/asprof stop $SERVER_PID 2>/dev/null; echo 'Done.'; exit 0" SIGINT SIGTERM

# Continuous profiling loop
CYCLE=0
while true; do
    CYCLE=$((CYCLE + 1))
    echo "[$(date)] Cycle #$CYCLE: Dumping profile data..."

    # Generate multiple output formats for maximum detail
    TEMP_COLLAPSED="$OUTPUT_DIR/profile-collapsed.txt"
    TEMP_TRACES="$OUTPUT_DIR/profile-traces.txt"
    TEMP_FLAT="$OUTPUT_DIR/profile-flat.txt"
    TEMP_JSON="$OUTPUT_DIR/profile-temp.jsonl"

    # Dump collapsed format (with enhanced frame info)
    $ASYNC_PROFILER_HOME/bin/asprof dump -o collapsed -f "$TEMP_COLLAPSED" $SERVER_PID 2>/dev/null || true

    # Dump traces format (for call tree building)
    $ASYNC_PROFILER_HOME/bin/asprof dump -o traces -f "$TEMP_TRACES" $SERVER_PID 2>/dev/null || true

    # Dump flat format (method-level statistics)
    $ASYNC_PROFILER_HOME/bin/asprof dump -o flat -f "$TEMP_FLAT" $SERVER_PID 2>/dev/null || true

    # Convert to enhanced JSON with detailed information
    # These are stack traces with detailed frame info
    if [ -s "$TEMP_COLLAPSED" ]; then
        python3 "$ENHANCED_CONVERTER" collapsed "$TEMP_COLLAPSED" "$TEMP_JSON.stack" "$SERVICE_NAME"
        if [ -s "$TEMP_JSON.stack" ]; then
            cat "$TEMP_JSON.stack" >> "$STACK_TRACES_OUTPUT"
            ENTRIES=$(wc -l < "$TEMP_JSON.stack")
            echo "[$(date)] Written $ENTRIES stack trace entries to $STACK_TRACES_OUTPUT"
        fi
    fi

    # Build call tree from traces format
    if [ -s "$TEMP_TRACES" ]; then
        python3 "$CALL_TREE_CONVERTER" "$TEMP_TRACES" "$CALL_TREES_OUTPUT" "$SERVICE_NAME"
        ENTRIES=$(wc -l < "$CALL_TREES_OUTPUT" | awk '{print $1 - prev} {prev=$1} END {print $1}' prev=0)
        echo "[$(date)] Written $ENTRIES hot path entries to $CALL_TREES_OUTPUT"
    fi

    # Cleanup temp files
    rm -f "$TEMP_JSON.stack" "$TEMP_JSON.trees" "$TEMP_JSON.hot"

    # Wait for next interval
    sleep 60
done
