# Span Link 구현 완료 보고서

## 📋 개요
**날짜**: 2026-03-27
**기능**: Jaeger API를 사용한 실시간 Span Link 생성
**상태**: ✅ 완료 및 테스트 성공

## 🎯 구현 목표
test1과 test2 서비스에서 **같은 userId**를 가진 span 간에 **OpenTelemetry 표준 Span Link**를 실시간으로 생성

## 🏗️ 아키텍처

### 1. Span Link 생성 흐름
```
test2 요청 (userId: FINAL_DEDUP_TEST)
    ↓
test2 span 생성 (traceID: 32f53d..., spanID: 456e0...)
    ↓
[10초 대기 - Jaeger 인덱싱 대기]
    ↓
test1 요청 (같은 userId)
    ↓
┌─────────────────────────────────────────┐
│ SpanAdvice.onMethodEnter()               │
│ 1. userId 추출                           │
│ 2. Jaeger API 호출                       │
│    → GET /api/traces?service=...&tag=arthas.attribute.userId:FINAL_DEDUP_TEST │
│ 3. 같은 userId를 가진 test2 span 검색     │
│ 4. SpanContext 생성                      │
│ 5. SpanBuilder.addLink()으로 link 추가    │
│ 6. test1 span 생성 (links 포함)          │
└─────────────────────────────────────────┘
```

### 2. 주요 컴포넌트

#### JaegerLinkLookupService
- **역할**: Jaeger API로 span 조회
- **주요 메서드**:
  - `findTargetSpanContexts()` - userId로 span 검색
  - `parseJaegerResponse()` - JSON 응답 파싱
  - `createSpanContext()` - SpanContext 생성

#### SpanHelper
- **역할**: OpenTelemetry Span 생성 및 관리
- **변경사항**: `createSpanWithLinks()` 메서드 추가
```java
public static SpanHelper createSpanWithLinks(String methodName, List<SpanContext> linkedContexts) {
    SpanBuilder builder = tracer.spanBuilder(methodName);
    for (SpanContext context : linkedContexts) {
        builder.addLink(context);  // OpenTelemetry 표준 link
    }
    return new SpanHelper(builder.startSpan(), span.makeCurrent());
}
```

#### SpanAdvice
- **역할**: ByteBuddy Advice에서 Span Link 로직 통합
- **변경사항**:
  - `findLinkedContexts()` 메서드 추가 (public)
  - test1 ↔ test2 링크 로직 구현
  - 중복 제거 로직 추가

## ✅ 구현 상세

### 1. Jaeger API 연동
```java
// Jaeger v2 API 형식
GET http://localhost:16686/api/traces?service=unknown_service:java&tag=arthas.attribute.userId:{userId}&limit=10

// 응답 예시
{
  "data": [{
    "traceID": "...",
    "spans": [{
      "spanID": "...",
      "operationName": "...",
      "tags": [{"key": "arthas.attribute.userId", "value": "..."}]
    }]
  }]
}
```

### 2. SpanContext 생성
- **문제**: `ImmutableSpanContext` 클래스가 SDK 내부에 있어 접근 불가
- **해결**: `SpanContext` 인터페이스를 직접 구현한 `SimpleSpanContext` 클래스 생성
```java
private static class SimpleSpanContext implements SpanContext {
    private final String traceId;
    private final String spanId;
    // ... 구현
}
```

### 3. JSON 파싱
- **문제**: 정규식이 복잡한 중첩 JSON을 처리하지 못함
- **해결**:
  1. traceID 패턴 매칭
  2. 각 trace 섹션에서 spanID와 operationName 추출
  3. 전역 Set을 사용한 중복 제거

### 4. 중복 제거
```java
Set<String> seenSpanKeys = new HashSet<>();
// ...
String uniqueKey = traceId + ":" + spanId;
if (!seenSpanKeys.contains(uniqueKey)) {
    seenSpanKeys.add(uniqueKey);
    spans.add(new SpanRef(traceId, spanId));
}
```

## 🧪 테스트 결과

### 테스트 시나리오
```
1. test2 요청: userId=FINAL_DEDUP_TEST
2. 10초 대기 (Jaeger 인덱싱 대기)
3. test1 요청: 같은 userId
```

### OpenSearch 검증
```json
// test2 span (먼저 생성된 span)
{
  "traceID": "32f53d4013f3082eedc844f52afb226d",
  "spanID": "456e023b3889e621",
  "references": null  // 링크 없음
}

// test1 span (나중에 생성된 span, 링크 있음)
{
  "traceID": "53bd7819af2baae9e05057d0e0897fcd",
  "spanID": "52bc0940a6b6f8ba",
  "references": [{
    "refType": "FOLLOWS_FROM",
    "traceID": "32f53d4013f3082eedc844f52afb226d",  // ← test2의 traceID
    "spanID": "456e023b3889e621"                      // ← test2의 spanID
  }]
}
```

### Jaeger UI 확인
- URL: http://localhost:16686
- Service: `unknown_service:java`
- Tag: `arthas.attribute.userId = FINAL_DEDUP_TEST`
- **결과**: test1 span에서 test2 span으로의 link가 시각적으로 표시됨

### 서비스 로그
```
[SpanAdvice] Looking for links: service=unknown_service:java, operation=..., userId=FINAL_DEDUP_TEST
[JaegerLink] Querying: http://localhost:16686/api/traces?...
[JaegerLink] Matched span: traceID=32f53d..., spanID=456e0...
[JaegerLink] Creating SpanContext: traceID=32f53d..., spanID=456e0...
[JaegerLink] Found span - traceID: 32f53d..., spanID: 456e0...
[JaegerLink] Found 1 matching spans  ← 중복 제거 성공!
>>> DEBUG: userId=FINAL_DEDUP_TEST
>>> Set attribute: userId = FINAL_DEDUP_TEST
```

## 📊 성공 지표

| 항목 | 상태 |
|------|------|
| Jaeger API 연동 | ✅ 성공 |
| SpanContext 생성 | ✅ 성공 |
| OpenTelemetry Link 생성 | ✅ 성공 |
| OpenSearch 저장 | ✅ 성공 |
| Jaeger UI 표시 | ✅ 성공 |
| 중복 제거 | ✅ 성공 |
| 실시간 생성 | ✅ 성공 |

## 🔧 문제 해결 이력

### 문제 1: HTTP 400 에러
- **원인**: Jaeger API 태그 형식 오류 (`%3D` 대신 `:` 사용 필요)
- **해결**: `tag=key:value` 형식으로 수정

### 문제 2: JSON 파싱 실패
- **원인**: 정규식이 복잡한 중첩 JSON 구조를 처리하지 못함
- **해결**: 더 강력한 파싱 알고리즘 구현

### 문제 3: ClassNotFoundException
- **원인**: `ImmutableSpanContext`가 SDK 내부 클래스
- **해결**: `SimpleSpanContext` 직접 구현

### 문제 4: 중복 Link 생성
- **원인**: 같은 span이 여러 번 매칭됨
- **해결**: 전역 Set을 사용한 중복 제거

## 📝 관련 파일

| 파일 | 변경사항 |
|------|----------|
| `JaegerLinkLookupService.java` | 새로 생성 - Jaeger API 연동 |
| `SpanHelper.java` | `createSpanWithLinks()` 메서드 추가 |
| `SpanAdvice.java` | `findLinkedContexts()` 메서드 추가, 링크 로직 통합 |
| `bytebuddy-advice/pom.xml` | OpenTelemetry SDK 의존성 추가 |

## 🚀 다음 단계

1. **배포**: 프로덕션 환경에 배포
2. **확장**: 다른 서비스 간 링크 생성 지원
3. **최적화**: Jaeger API 캐싱으로 성능 개선
4. **모니터링**: Link 생성 메트릭 수집

## 📌 참고 사항

- **Jaeger 인덱싱 딜레이**: span이 Jaeger에 나타나기까지 약 5-10초 소요
- **Link 타입**: `FOLLOWS_FROM` 사용 (OpenTelemetry 표준)
- **userId 속성**: `arthas.attribute.userId` 키로 저장
- **실시간 생성**: span이 생성될 때 자동으로 링크 생성
