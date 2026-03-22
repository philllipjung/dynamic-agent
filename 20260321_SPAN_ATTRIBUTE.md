# Span Attribute Feature (스팬 속성 기능)

## 개요

메서드 파라미터를 OpenTelemetry Span 속성으로 자동 캡처하는 기능입니다. Arthas `watch` 명령어로 파라미터를 탐색하고, 선택한 파라미터를 스팬 속성으로 저장합니다.

**버전**: 1.1.0
**생성일**: 2026-03-21
**수정일**: 2026-03-22

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Span Attribute Capture Flow                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 파라미터 탐색 (Arthas Watch)                                            │
│     ┌──────────────┐                                                        │
│     │ POST /api/   │                                                        │
│     │ arthas/watch │                                                        │
│     └──────────────┘                                                        │
│            ↓                                                                │
│     ┌──────────────┐                                                        │
│     │ArthasManager │ watch com.example.Service.processOrder                │
│     │              │ '{params[0], params[1]}' -n 1                         │
│     └──────────────┘                                                        │
│            ↓                                                                │
│     ┌─────────────────────────────────────────────┐                        │
│     │          Watch 응답 (JSON)                 │                        │
│     │  "params[0] = \"user-123\""                │                        │
│     │  "params[1] = \"order-456\""               │                        │
│     └─────────────────────────────────────────────┘                        │
│            │                                                                 │
│     ┌──────┴──────┐                                                          │
│     ▼             ▼                                                          │
│ [0: userId]  [1: orderId]  ← 사용자가 UI에서 선택                             │
│     │             │                                                          │
│     └──────┬──────┘                                                          │
│            ↓                                                                │
│  2. 매핑 저장 (Redis)                                                        │
│     ┌──────────────┐                                                        │
│     │ POST /api/   │                                                        │
│     │ bytebuddy/   │                                                        │
│     │createSpan    │                                                        │
│     │ Attribute    │                                                        │
│     └──────────────┘                                                        │
│            ↓                                                                │
│     ┌─────────────────────────────────────────────┐                        │
│     │  {                                         │                        │
│     │    "className": "com.example.Service",     │                        │
│     │    "methodName": "processOrder",           │                        │
│     │    "parameterMapping": {                   │                        │
│     │      "0": "userId",                        │                        │
│     │      "1": "orderId"                        │                        │
│     │    }                                       │                        │
│     │  }                                        │                        │
│     └─────────────────────────────────────────────┘                        │
│            ↓                                                                │
│     ┌──────────────┐                                                        │
│     │   Redis      │ Key: paramMapping:com.example.Service:processOrder   │
│     │              │ Value: {"0": "userId", "1": "orderId"}                │
│     └──────────────┘                                                        │
│            ↓                                                                │
│  3. Advice 적용 (ByteBuddy)                                                 │
│     ┌─────────────────────────────────────────────┐                        │
│     │  SpanAttributeAdvice.onMethodEnter()       │                        │
│     │                                             │                        │
│     │  // Redis에서 매핑 조회                      │                        │
│     │  Map<Integer, String> mapping =             │                        │
│     │    getParameterMapping(className,           │                        │
│     │                      methodName);           │                        │
│     │                                             │                        │
│     │  // 파라미터 값을 속성으로 설정              │                        │
│     │  for (Map.Entry entry : mapping) {          │                        │
│     │    int idx = entry.getKey();                │                        │
│     │    String name = entry.getValue();          │                        │
│     │    Object value = allArguments[idx];        │                        │
│     │    helper.setAttribute(                      │                        │
│     │      "arthas.attribute." + name,            │                        │
│     │      String.valueOf(value)                  │                        │
│     │    );                                       │                        │
│     │  }                                          │                        │
│     └─────────────────────────────────────────────┘                        │
│            ↓                                                                │
│  4. OpenSearch에 인덱싱                                                      │
│     ┌──────────────┐                                                        │
│     │  OpenSearch  │ tags: [                                              │
│     │              │   {key: "arthas.attribute.userId", value: "user-123"}│
│     │              │   {key: "arthas.attribute.orderId", value: "ord-456"}│
│     │              │ ]                                                      │
│     └──────────────┘                                                        │
│            ↓                                                                │
│     UI에서 검색 가능                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 구성 요소

### 1. ArthasManager (Watch 실행)

**위치**: `arthas-manager/src/main/java/com/javaagent/arthas/ArthasManager.java`

```java
public static String watch(
    String className,
    String methodName,
    String condition,
    int numberOfIterations
) {
    String command = String.format(
        "watch %s %s '{params}' -n %d -x 2 %s",
        className,
        methodName,
        numberOfIterations,
        condition != null && !condition.isEmpty() ? " -c '" + condition + "'" : ""
    );

    return executeTunnelApiCommand(pid, command);
}
```

### 2. ParameterMappingService (Redis 저장)

**위치**: `bytebuddy-agent/src/main/java/com/javaagent/bytebuddy/redis/ParameterMappingService.java`

```java
public static void saveMapping(String className, String methodName,
                               Map<Integer, String> mapping) {
    try (Jedis jedis = jedisPool.getResource()) {
        String key = KEY_PREFIX + className + ":" + methodName;

        // Map<Integer, String>을 JSON으로 변환
        Map<String, String> stringMap = new HashMap<>();
        for (Map.Entry<Integer, String> entry : mapping.entrySet()) {
            stringMap.put(entry.getKey().toString(), entry.getValue());
        }

        String jsonValue = objectMapper.writeValueAsString(stringMap);
        jedis.set(key, jsonValue);
    }
}

public static Map<Integer, String> getMapping(String className, String methodName) {
    try (Jedis jedis = jedisPool.getResource()) {
        String key = KEY_PREFIX + className + ":" + methodName;
        String jsonValue = jedis.get(key);

        if (jsonValue == null) {
            return null;
        }

        // JSON을 Map<Integer, String>으로 변환
        Map<String, String> stringMap = objectMapper.readValue(jsonValue, Map.class);
        Map<Integer, String> intMap = new HashMap<>();
        for (Map.Entry<String, String> entry : stringMap.entrySet()) {
            intMap.put(Integer.parseInt(entry.getKey()), entry.getValue());
        }

        return intMap;
    }
}
```

**Redis 데이터 구조**:
```
Key: "paramMapping:{className}:{methodName}"
Value: {"0": "userId", "1": "orderId"}
```

### 3. ByteBuddyAgent (Advice 적용)

**위치**: `bytebuddy-agent/src/main/java/com/javaagent/bytebuddy/ByteBuddyAgent.java`

```java
public static String createSpanAttributeAdvice(
    String className,
    String methodName,
    Map<Integer, String> paramMapping
) {
    // 매핑을 Advice에 저장
    com.javaagent.bytebuddy.advices.SpanAttributeAdvice.setParameterMapping(
        className, methodName, paramMapping
    );

    // Advice 적용
    new AgentBuilder.Default()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .type(ElementMatchers.named(className))
        .transform(new AgentBuilder.Transformer() {
            @Override
            public DynamicType.Builder<?> transform(
                    DynamicType.Builder<?> builder,
                    TypeDescription typeDescription,
                    ClassLoader classLoader,
                    JavaModule module,
                    ProtectionDomain protectionDomain) {

                // Helper 주입
                if (classLoader != null) {
                    injectHelper(classLoader);
                }

                // Advice 적용
                return builder.visit(
                    Advice.to(com.javaagent.bytebuddy.advices.SpanAttributeAdvice.class)
                        .on(ElementMatchers.named(methodName)
                                .and(ElementMatchers.isMethod())
                                .and(ElementMatchers.not(ElementMatchers.isStatic())))
                );
            }
        })
        .installOn(instrumentation);

    return "SUCCESS: Span attribute advice created";
}
```

### 4. SpanAttributeAdvice (런타임 처리)

**위치**: `bytebuddy-advice/src/main/java/com/javaagent/bytebuddy/advices/SpanAttributeAdvice.java`

```java
public class SpanAttributeAdvice {

    private static Map<Integer, String> getParameterMapping(String className, String methodName) {
        try {
            // Reflection으로 Redis 접근
            Class<?> redisService = Class.forName(
                "com.javaagent.bytebuddy.redis.ParameterMappingService");
            Method getMethod = redisService.getMethod("getMapping", String.class, String.class);
            Map<Integer, String> mapping = (Map<Integer, String>)
                getMethod.invoke(null, className, methodName);

            if (mapping != null) {
                System.out.println("[SpanAttributeAdvice] Loaded mapping from Redis: " +
                    className + "." + methodName + " -> " + mapping);
                return mapping;
            }
        } catch (Exception e) {
            System.out.println("[SpanAttributeAdvice] Redis lookup failed: " + e.getMessage());
        }
        return Collections.emptyMap();
    }

    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Method method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] allArguments
    ) {
        try {
            String className = target.getClass().getName();
            String methodName = method != null ? method.getName() : "unknown";

            // 매핑 조회
            Map<Integer, String> mapping = getParameterMapping(className, methodName);

            if (mapping.isEmpty()) {
                return;
            }

            // 스팬 생성
            Tracer tracer = getTracer();
            Span span = tracer.spanBuilder(className + "." + methodName)
                    .setParent(io.opentelemetry.context.Context.current())
                    .startSpan();

            // 속성 설정
            SpanAttributeHelper attrHelper = new SpanAttributeHelper(span);
            for (Map.Entry<Integer, String> entry : mapping.entrySet()) {
                int paramIndex = entry.getKey();
                String paramName = entry.getValue();
                Object paramValue = allArguments[paramIndex];

                // arthas.attribute. 접두사 추가
                String attrKey = "arthas.attribute." + paramName;
                String attrValue = paramValue != null ? String.valueOf(paramValue) : "null";

                attrHelper.setAttribute(attrKey, attrValue);
            }

            span.end();

        } catch (Exception e) {
            System.err.println("[SpanAttributeAdvice] Error: " + e.getMessage());
        }
    }
}
```

### 5. SpanAttributeHelper

**위치**: `bytebuddy-advice/src/main/java/com/javaagent/bytebuddy/helper/SpanAttributeHelper.java`

```java
public class SpanAttributeHelper {
    private final Span span;

    public SpanAttributeHelper(Span span) {
        this.span = span;
    }

    public void setAttribute(String key, String value) {
        if (span != null && value != null) {
            span.setAttribute(key, value);
        }
    }
}
```

## REST API

### 1. Watch로 파라미터 탐색

```bash
POST /api/arthas/watch
Content-Type: application/json

{
  "className": "com.example.DriverService",
  "methodName": "findNearbyDrivers",
  "condition": "params[0] != null",
  "numberOfIterations": 1
}
```

**응답**:
```json
{
  "success": true,
  "output": "params[0] = \"user-123\"\nparams[1] = Location(lat:37.5, lng:127.0)"
}
```

### 2. 스팬 속성 캡처 생성

```bash
POST /api/bytebuddy/createSpanAttribute
Content-Type: application/json

{
  "className": "com.example.DriverService",
  "methodName": "findNearbyDrivers",
  "parameterMapping": {
    "0": "userId",
    "1": "location"
  }
}
```

**응답**:
```json
{
  "success": true,
  "message": "SUCCESS: Span attribute advice created for com.example.DriverService.findNearbyDrivers"
}
```

### 3. Watch + 속성 생성 (통합)

```bash
POST /api/bytebuddy/watchAndCreateSpanAttribute
Content-Type: application/json

{
  "className": "com.example.DriverService",
  "methodName": "findNearbyDrivers",
  "condition": "params[0] != null",
  "numberOfIterations": 1
}
```

**응답**:
```json
{
  "success": true,
  "watchOutput": "params[0] = \"user-123\"\nparams[1] = Location(...)",
  "parameters": [
    {"index": 0, "name": "param0", "value": "\"user-123\"", "type": "String"},
    {"index": 1, "name": "param1", "value": "Location(...)", "type": "Location"}
  ]
}
```

## 설정

### application.properties

```properties
# Arthas
arthas.tunnel.server.host=127.0.0.1
arthas.tunnel.server.port=8563

# Redis
spring.redis.host=localhost
spring.redis.port=6379

# OpenSearch
opensearch.host=http://localhost:9200
opensearch.index.pattern=jaeger-span-*
```

### AgentConstants

```java
// Arthas
public static final String DEFAULT_ARTHAS_HOST = "127.0.0.1";
public static final int DEFAULT_ARTHAS_PORT = 8563;
public static final String PROP_ARTHAS_HOST = "arthas.tunnel.server.host";
public static final String PROP_ARTHAS_PORT = "arthas.tunnel.server.port";

// Redis
public static final String DEFAULT_REDIS_HOST = "localhost";
public static final int DEFAULT_REDIS_PORT = 6379;
public static final String PROP_REDIS_HOST = "spring.redis.host";
public static final String PROP_REDIS_PORT = "spring.redis.port";

// OpenSearch
public static final String DEFAULT_OPENSEARCH_HOST = "http://localhost:9200";
public static final String DEFAULT_OPENSEARCH_INDEX = "jaeger-span-*";
public static final String PROP_OPENSEARCH_HOST = "opensearch.host";
public static final String PROP_OPENSEARCH_INDEX = "opensearch.index.pattern";
```

## 사용 예시

### 예시 1: 단일 파라미터 캡처

```bash
# 1. Watch로 파라미터 확인
curl -X POST http://localhost:8080/api/arthas/watch \
  -H "Content-Type: application/json" \
  -d '{
    "className": "com.example.DriverService",
    "methodName": "findNearbyDrivers",
    "numberOfIterations": 1
  }'

# 응답: params[0] = "user-123"

# 2. 속성 캡처 설정
curl -X POST http://localhost:8080/api/bytebuddy/createSpanAttribute \
  -H "Content-Type: application/json" \
  -d '{
    "className": "com.example.DriverService",
    "methodName": "findNearbyDrivers",
    "parameterMapping": {
      "0": "userId"
    }
  }'

# 3. 메서드 실행 시 자동으로 속성 캡처
# OpenSearch: tags.arthas.attribute.userId = "user-123"
```

### 예시 2: 여러 파라미터 캡처

```bash
curl -X POST http://localhost:8080/api/bytebuddy/createSpanAttribute \
  -H "Content-Type: application/json" \
  -d '{
    "className": "com.example.OrderService",
    "methodName": "createOrder",
    "parameterMapping": {
      "0": "userId",
      "1": "orderId",
      "2": "amount"
    }
  }'
```

### 예시 3: 통합 API 사용

```bash
# 한 번에 Watch + 속성 생성
curl -X POST http://localhost:8080/api/bytebuddy/watchAndCreateSpanAttribute \
  -H "Content-Type: application/json" \
  -d '{
    "className": "com.example.DriverService",
    "methodName": "findNearbyDrivers",
    "numberOfIterations": 1
  }'

# UI에서 파라미터를 선택하고 속성 생성
```

## 속성 네이밍 규칙

모든 속성은 `arthas.attribute.` 접두사를 사용합니다:

```java
// 올바른 예
setAttribute("arthas.attribute.userId", "user-123");
setAttribute("arthas.attribute.orderId", "order-456");
setAttribute("arthas.attribute.amount", "100.50");

// 잘못된 예 (OpenSearch에서 검색 불가)
setAttribute("userId", "user-123");
setAttribute("orderId", "order-456");
```

## OpenSearch에서 검색

```bash
# userId로 스팬 검색
curl -X POST "http://localhost:9200/jaeger-span-*/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "bool": {
        "must": [
          {"term": {"tags.key": "arthas.attribute.userId"}},
          {"term": {"tags.value": "user-123"}}
        ]
      }
    }
  }'
```

## 제한사항

### 1. ClassLoader 격리

`bytebuddy-advice`는 `bytebuddy-agent`를 직접 import할 수 없습니다.

**해결책**: Reflection 사용

### 2. 매핑 저장

매핑은 Redis에 저장되므로 Redis가 실행 중이어야 합니다.

### 3. 속성 타입

모든 속성은 String으로 변환됩니다.

## 관련 문서

- [20260320_SPAN_CREATE.md](./20260320_SPAN_CREATE.md) - 스팬 생성 기능
- [20260322_SPAN_LINK.md](./20260322_SPAN_LINK.md) - 스팬 링크 기능
- [bytebuddy-agent README](./bytebuddy-agent/README.md)
- [bytebuddy-advice README](./bytebuddy-advice/README.md)
