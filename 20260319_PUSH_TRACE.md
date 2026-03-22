# Jaeger OTLP 트레이스 전송 가이드

## Docker Compose 실행

```bash
# OpenSearch + Jaeger 시작
docker-compose -f C:/Users/EUROPE/agent/jaeger-main/docker-compose/monitor/docker-compose-opensearch.yml up -d

# 컨테이너 상태 확인
docker-compose -f C:/Users/EUROPE/agent/jaeger-main/docker-compose/monitor/docker-compose-opensearch.yml ps
```

## 서비스 접속

| 서비스 | URL |
|--------|-----|
| Jaeger UI | http://localhost:16686 |
| OpenSearch | http://localhost:9200 |
| OpenSearch Dashboards | http://localhost:5601 |
| OTLP HTTP | http://localhost:4318/v1/traces |
| OTLP gRPC | http://localhost:4317 |

## 트레이스 전송 (curl)

### 성공 예제

```bash
curl -X POST -H 'Content-Type: application/json' http://localhost:4318/v1/traces -d '{
  "resourceSpans": [{
    "resource": {
      "attributes": [{
        "key": "service.name",
        "value": {"stringValue": "my-service"}
      }]
    },
    "scopeSpans": [{
      "scope": {"name": "my-library"},
      "spans": [{
        "traceId": "01010101010101010101010101010101",
        "spanId": "0101010101010101",
        "name": "my-operation",
        "startTimeUnixNano": 1790309230000000000,
        "endTimeUnixNano": 1790309231000000000
      }]
    }]
  }]
}'
```

### 주의사항

1. **Timestamp 형식**: `startTimeUnixNano`, `endTimeUnixNano`은 **숫자**로 작성 (따옴표 없이)
2. **현재 시간 사용**: 너무 오래된 timestamp는 무시될 수 있음
3. **특수 문자 이스케이프**: 느낌표(!) 같은 문자는 JSON 이스케이프 필요

### Bash에서 현재 시간으로 전송

```bash
NANO=$(($(date +%s%N)/1000000))000000
END_NANO=$((NANO + 1000000000))

curl -X POST -H 'Content-Type: application/json' http://localhost:4318/v1/traces -d "{
  \"resourceSpans\": [{
    \"resource\": {
      \"attributes\": [{
        \"key\": \"service.name\",
        \"value\": {\"stringValue\": \"my-service\"}
      }]
    },
    \"scopeSpans\": [{
      \"scope\": {\"name\": \"my-scope\"},
      \"spans\": [{
        \"traceId\": \"01010101010101010101010101010101\",
        \"spanId\": \"0101010101010101\",
        \"name\": \"my-operation\",
        \"startTimeUnixNano\": $NANO,
        \"endTimeUnixNano\": $END_NANO
      }]
    }]
  }]
}"
```

## 트레이스 조회

```bash
# 서비스 목록
curl -s "http://localhost:16686/api/services"

# 특정 서비스 트레이스 검색
curl -s "http://localhost:16686/api/traces?service=my-service"

# OpenSearch 직접 조회
curl -s "http://localhost:9200/jaeger-span-$(date +%Y-%m-%d)/_search?q=service.name:my-service"
```

## 자주 쓰는 Docker 명령어

```bash
# 로그 확인
docker logs monitor-jaeger-1 --tail 50

# 실시간 로그
docker logs monitor-jaeger-1 -f

# 컨테이너 중지 및 삭제
docker-compose -f C:/Users/EUROPE/agent/jaeger-main/docker-compose/monitor/docker-compose-opensearch.yml down
```

## OTLP 트레이스 JSON 구조

```
resourceSpans[]
  └─ resource
       └─ attributes[] → service.name (필수)
  └─ scopeSpans[]
       └─ scope
            └─ name, version
       └─ spans[]
            ├─ traceId (16바이트 hex)
            ├─ spanId (8바이트 hex)
            ├─ name
            ├─ startTimeUnixNano (숫자)
            ├─ endTimeUnixNano (숫자)
            ├─ kind (1=INTERNAL, 2=SERVER, 3=CLIENT, 4=PRODUCER, 5=CONSUMER)
            └─ attributes[] (선택)
```
