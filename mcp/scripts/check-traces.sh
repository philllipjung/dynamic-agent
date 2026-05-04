#!/bin/bash

echo "=== Trace Pipeline Status ==="
echo ""

TODAY=$(date +%Y-%m-%d)

# 1. Check Microsim (trace generator)
echo "1. Microsim Status:"
if ps aux | grep "microsim.*-d" | grep -v grep | grep -q .; then
    echo "   ✅ Microsim is running"
else
    echo "   ❌ Microsim is NOT running"
fi

# 2. Check Jaeger (OTLP receiver)
echo ""
echo "2. Jaeger OTLP Receiver:"
if ss -tlnp 2>/dev/null | grep -q ":4318"; then
    echo "   ✅ OTLP HTTP receiver listening (port 4318)"
else
    echo "   ❌ OTLP HTTP receiver NOT listening"
fi

# 3. Check Jaeger API
echo ""
echo "3. Jaeger API:"
SERVICES=$(curl -s "http://localhost:16686/api/services" 2>/dev/null | python3 -c "import sys, json; d=json.load(sys.stdin); print(len(d.get('data', [])))" 2>/dev/null || echo "0")
if [ "$SERVICES" -gt 0 ]; then
    echo "   ✅ Jaeger API responding ($SERVICES services)"
else
    echo "   ❌ Jaeger API NOT responding"
fi

# 4. Check OpenSearch
echo ""
echo "4. OpenSearch:"
if curl -s http://localhost:9200/_cluster/health > /dev/null 2>&1; then
    echo "   ✅ OpenSearch is responding"
else
    echo "   ❌ OpenSearch NOT responding"
fi

# 5. Check today's index
echo ""
echo "5. Today's Index (jaeger-span-$TODAY):"
SPAN_COUNT=$(curl -s -X POST "http://localhost:9200/jaeger-span-$TODAY/_search" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"match_all":{}},"size":0}' 2>/dev/null | \
  python3 -c "import sys, json; d=json.load(sys.stdin); print(d['hits']['total']['value'])" 2>/dev/null || echo "0")

if [ "$SPAN_COUNT" -gt 0 ]; then
    echo "   ✅ Has $SPAN_COUNT spans"
else
    echo "   ❌ No spans found"
fi

# 6. Check most recent span time
echo ""
echo "6. Most Recent Span:"
LATEST_INFO=$(curl -s -X POST "http://localhost:9200/jaeger-span-$TODAY/_search" \
  -H 'Content-Type: application/json' \
  -d '{"size":1,"sort":[{"startTime":"desc"}]}' 2>/dev/null)

if [ -n "$LATEST_INFO" ]; then
    LATEST_SPAN=$(echo "$LATEST_INFO" | python3 -c "import sys, json, datetime; d=json.load(sys.stdin); h=d['hits']['hits'][0]; ms=h['_source']['startTimeMillis']; dt=datetime.datetime.fromtimestamp(ms/1000); print(dt)" 2>/dev/null)
    
    if [ -n "$LATEST_SPAN" ]; then
        NOW_EPOCH=$(date +%s)
        LATEST_EPOCH=$(date -d "$LATEST_SPAN" +%s 2>/dev/null || echo "0")
        DIFF=$((NOW_EPOCH - LATEST_EPOCH))

        if [ $DIFF -lt 60 ]; then
            echo "   ✅ $LATEST_SPAN (${DIFF}s ago) - RECEIVING TRACES"
        else
            echo "   ⚠️  $LATEST_SPAN (${DIFF}s ago) - STALE"
        fi
    else
        echo "   ❌ Could not parse span time"
    fi
else
    echo "   ❌ No spans found"
fi

echo ""
echo "=== Status Complete ==="
