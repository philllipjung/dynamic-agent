# JSON 스키마 분석 문서 (v2)

## 개요

이 문서는 `stack-traces.jsonl`과 `call-trees.jsonl` 파일의 JSON 스키마 v2를 상세히 분석합니다.

**v2 최적화 내용:**
- 중복 필드 6개 제거 (33% 필드 감소: 16개 → 10개)
- 필드 순서 최적화 (메타데이터 → 큰 데이터)
- call-trees.jsonl Hot Path에 duration_ns 추가

---

## 1. stack-traces.jsonl 스키마 v2

### 파일 형식

- **형식**: JSONL (JSON Lines)
- **인코딩**: UTF-8
- **설명**: 개별 스택 추적 레코드, 한 줄당 하나의 JSON 객체

### 전체 스키마

```json
{
  "type": "object",
  "description": "단일 스택 추적 레코드 (v2 최적화됨)",
  "properties": {
    "service": { "type": "string" },
    "profiler": { "type": "string" },
    "profiler_version": { "type": "string" },
    "timestamp": { "type": "string" },
    "thread_id": { "type": "integer" },
    "thread_name": { "type": "string" },
    "sample_count": { "type": "integer" },
    "stack_depth": { "type": "integer" },
    "frame_types": { "type": "object" },
    "stack": { "type": "array", "items": { "type": "object" } }
  },
  "required": ["service", "profiler", "profiler_version", "timestamp", "thread_id", "thread_name", "sample_count", "stack_depth", "frame_types", "stack"]
}
```

### v2 최적화 변경사항

#### 제거된 필드 (6개)

| 필드 | 이유 | 대체 방법 |
|------|------|----------|
| `top_frame` | `stack[0]`와 100% 중복 | `trace['stack'][0]` 사용 |
| `bottom_frame` | `stack[-1]`와 100% 중복 | `trace['stack'][-1]` 사용 |
| `has_kernel_frames` | `frame_types.kernel > 0`으로 계산 가능 | `trace['frame_types']['kernel'] > 0` |
| `has_java_frames` | `frame_types.java > 0`으로 계산 가능 | `trace['frame_types']['java'] > 0` |
| `java_packages` | stack에서 추출 가능 | `[f['package'] for f in stack if f.get('package')]` |
| `java_classes` | stack에서 추출 가능 | `[f['class'] for f in stack if f.get('class')]` |

#### 필드 순서 변경

**v1 순서:** timestamp, service, profiler, **stack** (큰 데이터), ...

**v2 순서:** service, profiler, profiler_version, timestamp, thread_id, thread_name, sample_count, stack_depth, frame_types, **stack** (마지막)

→ 메타데이터를 먼저 배치하여 JSON 파싱 성능 향상

### 필드 상세 설명

#### 1. service (필수)

```json
"service": "server1"
```

- **타입**: `string`
- **설명**: 서비스 이름
- **예시**: `"server1"`

#### 2. profiler (필수)

```json
"profiler": "async-profiler"
```

- **타입**: `string`
- **설명**: 프로파일러 도구 이름
- **예시**: `"async-profiler"`

#### 3. profiler_version (필수)

```json
"profiler_version": "4.1"
```

- **타입**: `string`
- **설명**: 프로파일러 버전
- **예시**: `"4.1"`

#### 4. timestamp (필수)

```json
"timestamp": "2026-02-23T12:17:21.457903Z"
```

- **타입**: `string` (ISO 8601 형식)
- **형식**: `YYYY-MM-DDTHH:MM:SS.ssssssZ`
- **설명**: 프로필 샘플 수집 시간 (UTC)
- **예시**:
  - `"2026-02-23T12:17:21.457903Z"`
  - `"2026-03-31T02:27:23.569584Z"`

#### 5. thread_id (필수)

```json
"thread_id": 413434
```

- **타입**: `integer`
- **설명**: 스레드 ID (OS 수준)
- **범위**: 1 ~ 4,194,304+
- **참고**: `/proc/[pid]/task`에서 확인 가능

#### 6. thread_name (필수)

```json
"thread_name": "reactor-http-epoll-2"
```

- **타입**: `string` 또는 `null`
- **설명**: 스레드 이름
- **일반적인 이름 패턴**:
  - `reactor-http-epoll-N` (Netty 이벤트 루프)
  - `C2 CompilerThre` (JIT 컴파일러)
  - `G1 Refine#0` (GC 스레드)
  - `VM Periodic Tas` (VM 내부 스레드)

#### 7. sample_count (필수)

```json
"sample_count": 1
```

- **타입**: `integer`
- **범위**: 1 ~ N
- **설명**: 이 스택이 수집된 횟수 (중복 횟수)
- **의미**: 값이 클수록 핫 경로

#### 8. stack_depth (필수)

```json
"stack_depth": 133
```

- **타입**: `integer`
- **범위**: 1 ~ 200+
- **설명**: 스택 깊이 (총 프레임 수)
- **예시**:
  - `8` (얕은 스택, GC 스레드)
  - `133` (깊은 스택, HTTP 요청 처리)

#### 9. frame_types (필수)

```json
"frame_types": {
  "kernel": 6,
  "java": 2,
  "unknown": 0,
  "native": 0
}
```

- **타입**: `object`
- **설명**: 프레임 유형별 개수 집계
- **필드**:
  - `kernel`: 커널 프레임 수
  - `java`: Java 프레임 수
  - `unknown`: 알 수 없는 프레임 수
  - `native`: 네이티브 프레임 수

**v2 사용법:**
```python
# v1: 제거된 has_kernel_frames 필드
has_kernel = trace['has_kernel_frames']  # ❌ v1에서만 사용

# v2: frame_types로 계산
has_kernel = trace['frame_types']['kernel'] > 0  # ✅ v2
```

#### 10. stack (필수)

```json
"stack": [
  {
    "raw": "[G1 Service tid=413434]",
    "name": "[G1 Service tid=413434]",
    "type": "java",
    "is_kernel": false
  },
  {
    "raw": "__futex_abstimed_wait_cancelable64",
    "name": "__futex_abstimed_wait_cancelable64",
    "type": "java",
    "is_kernel": false
  }
]
```

- **타입**: `array` of `Frame objects`
- **설명**: 스택 프레임 배열 (위에서 아래로)
- **길이**: 1 ~ 200+ 프레임
- **순서**:
  - `[0]`: 최상위 프레임 (스택의 꼭대기, 가장 최근 호출)
  - `[-1]`: 최하위 프레임 (스택의 바닥, 시작점)

**v2 사용법:**
```python
# v1: 제거된 top_frame, bottom_frame 필드
top = trace['top_frame']        # ❌ v1에서만 사용
bottom = trace['bottom_frame']  # ❌ v1에서만 사용

# v2: stack 배열로 직접 접근
top = trace['stack'][0]         # ✅ v2
bottom = trace['stack'][-1]     # ✅ v2
```

##### Frame Object 스키마

```typescript
interface Frame {
  // 필수 필드
  raw: string;           // 원본 프레임 문자열
  name: string;          // 표시 이름
  type: FrameType;       // 프레임 유형
  is_kernel: boolean;    // 커널 프레임 여부

  // 선택적 필드 (Java 프레임만)
  method?: string;       // 메서드 이름
  class?: string;        // 클래스 이름
  package?: string;      // 패키지 이름
}

type FrameType = "java" | "kernel" | "native" | "unknown";
```

**Frame 필드 상세:**

| 필드 | 타입 | 필수 | 설명 | 예시 |
|------|------|------|------|------|
| `raw` | string | ✅ | 원본 프레임 문자열 | `"[reactor-http-epoll-2 tid=413535]"` |
| `name` | string | ✅ | 표시용 이름 (kernel인 경우 `_[k]` 제거) | `"reactor-http-epoll-2"` |
| `type` | string | ✅ | 프레임 유형 | `"java"`, `"kernel"`, `"native"`, `"unknown"` |
| `is_kernel` | boolean | ✅ | 커널 프레임 여부 | `true`, `false` |
| `method` | string | ❌ | 메서드 이름 (Java만) | `"run"`, `"invokeChannelRead"` |
| `class` | string | ❌ | 클래스 이름 (Java만) | `"Thread"`, `"EpollEventLoop"` |
| `package` | string | ❌ | 패키지 경로 (Java만) | `"java/lang"`, `"io/netty/channel"` |

**프레임 유형별 예시:**

```json
// Java 프레임 (메서드 정보 포함)
{
  "raw": "io/netty/channel/epoll/EpollEventLoop.run",
  "name": "EpollEventLoop.run",
  "type": "java",
  "is_kernel": false,
  "method": "run",
  "class": "EpollEventLoop",
  "package": "io/netty/channel/epoll"
}

// Java 프레임 (스레드 마커)
{
  "raw": "[reactor-http-epoll-2 tid=413535]",
  "name": "[reactor-http-epoll-2 tid=413535]",
  "type": "java",
  "is_kernel": false
}

// Kernel 프레임
{
  "raw": "do_syscall_64_[k]",
  "name": "do_syscall_64",
  "type": "kernel",
  "is_kernel": true
}

// Unknown 프레임
{
  "raw": "[unknown]",
  "name": "[unknown]",
  "type": "unknown",
  "is_kernel": false
}
```

**v2 사용법 (패키지/클래스 추출):**
```python
# v1: 제거된 java_packages, java_classes 필드
packages = trace['java_packages']  # ❌ v1에서만 사용
classes = trace['java_classes']    # ❌ v1에서만 사용

# v2: stack에서 직접 추출
packages = list(set(f['package'] for f in trace['stack'] if f.get('package')))  # ✅ v2
classes = list(set(f['class'] for f in trace['stack'] if f.get('class')))      # ✅ v2
```

### 실제 예제

#### 예제 1: HTTP 요청 처리 (깊은 스택)

```json
{
  "service": "server1",
  "profiler": "async-profiler",
  "profiler_version": "4.1",
  "timestamp": "2026-02-23T12:17:21.457903Z",
  "thread_id": 413535,
  "thread_name": "reactor-http-epoll-2",
  "sample_count": 1,
  "stack_depth": 133,
  "frame_types": {
    "kernel": 3,
    "java": 130,
    "unknown": 0,
    "native": 0
  },
  "stack": [
    {
      "raw": "[reactor-http-epoll-2 tid=413535]",
      "name": "[reactor-http-epoll-2 tid=413535]",
      "type": "java",
      "is_kernel": false
    },
    {
      "raw": "java/lang/Thread.run",
      "name": "java/lang/Thread.run",
      "type": "java",
      "is_kernel": false,
      "method": "run",
      "class": "Thread",
      "package": "java/lang"
    },
    {
      "raw": "io/netty/channel/epoll/EpollEventLoop.run",
      "type": "java",
      "is_kernel": false,
      "method": "run",
      "class": "EpollEventLoop",
      "package": "io/netty/channel/epoll"
    }
  ]
}
```

#### 예제 2: GC 스레드 (얕은 스택)

```json
{
  "service": "server1",
  "profiler": "async-profiler",
  "profiler_version": "4.1",
  "timestamp": "2026-02-23T12:17:21.457903Z",
  "thread_id": 413434,
  "thread_name": "G1 Service",
  "sample_count": 1,
  "stack_depth": 8,
  "frame_types": {
    "kernel": 6,
    "java": 2,
    "unknown": 0,
    "native": 0
  },
  "stack": [
    {
      "raw": "[G1 Service tid=413434]",
      "name": "[G1 Service tid=413434]",
      "type": "java",
      "is_kernel": false
    },
    {
      "raw": "__futex_abstimed_wait_cancelable64",
      "type": "java",
      "is_kernel": false
    },
    {
      "raw": "entry_SYSCALL_64_after_hwframe_[k]",
      "name": "entry_SYSCALL_64_after_hwframe",
      "type": "kernel",
      "is_kernel": true
    }
  ]
}
```

### 데이터 통계

전체 데이터셋에서의 통계:

| 통계 | 값 |
|------|-----|
| 평균 stack_depth | 80-100 프레임 |
| 최대 stack_depth | 200+ 프레임 |
| 평균 sample_count | 1.4 |
| 고유 thread_id | 33개 |
| kernel 프레임 비율 | 4.6% |
| java 프레임 비율 | 95.4% |

### 최적화 효과

| 항목 | v1 | v2 | 개선 |
|------|-----|-----|------|
| 필드 수 | 16개 | 10개 | **-37.5%** |
| 파일 크기 | 720KB | 681KB | **-5.4%** |
| 중복 데이터 | 6개 필드 | 0개 | **100% 제거** |

---

## 2. call-trees.jsonl 스키마

### 파일 형식

- **형식**: JSONL (JSON Lines)
- **인코딩**: UTF-8
- **설명**: 호출 트리 및 핫 경로 레코드
- **두 가지 유형**: `call_tree` (루트) + `hot_path` (개별 경로)

### 스키마 타입 1: Call Tree Root (profile_type: "call_tree")

```json
{
  "type": "object",
  "profile_type": "call_tree",
  "properties": {
    "timestamp": { "type": "string" },
    "service": { "type": "string" },
    "profiler": { "type": "string" },
    "profiler_version": { "type": "string" },
    "profile_type": { "type": "string", "enum": ["call_tree"] },
    "total_samples": { "type": "integer" },
    "total_traces": { "type": "integer" },
    "unique_packages": { "type": "integer" },
    "unique_classes": { "type": "integer" },
    "java_packages": { "type": "array", "items": { "type": "string" } },
    "java_classes": { "type": "array", "items": { "type": "string" } },
    "call_tree": { "type": "object" }
  }
}
```

#### Call Tree 필드 상세

##### 1-5. 기본 필드

```json
{
  "timestamp": "2026-03-31T02:31:15.278384Z",
  "service": "server1",
  "profiler": "async-profiler",
  "profiler_version": "4.1",
  "profile_type": "call_tree"
}
```

`stack-traces.jsonl`과 동일

##### 6. total_samples (필수)

```json
"total_samples": 112
```

- **타입**: `integer`
- **설명**: 총 샘플 수 (모든 추적 합계)

##### 7. total_traces (필수)

```json
"total_traces": 106
```

- **타입**: `integer`
- **설명**: 총 추적 수 (고유 스택 경로 수)

##### 8. unique_packages (필수)

```json
"unique_packages": 67
```

- **타입**: `integer`
- **설명**: 고유 Java 패키지 수

##### 9. unique_classes (필수)

```json
"unique_classes": 227
```

- **타입**: `integer`
- **설명**: 고유 Java 클래스 수

##### 10. java_packages (필수)

```json
"java_packages": [
  "com.fasterxml.jackson.databind",
  "io.netty.channel",
  "reactor.core.publisher"
]
```

- **타입**: `array` of `string`
- **설명**: 관측된 모든 Java 패키지 목록
- **순서**: 알파벳 순 (일반적)

##### 11. java_classes (필수)

```json
"java_classes": [
  "AbstractChannelHandlerContext",
  "EpollEventLoop",
  "InternalMonoOperator"
]
```

- **타입**: `array` of `string`
- **설명**: 관측된 모든 Java 클래스 목록
- **포함**: 내부 클래스 (예: `InnerClass$SubClass`)

##### 12. call_tree (필수)

```json
"call_tree": {
  "name": "root",
  "sample_count": 112,
  "self_samples": 0,
  "percentage": 99.94,
  "depth": 0,
  "path": [],
  "children": [],
  "thread_id": 4006,
  "thread_name": "C2 CompilerThre"
}
```

- **타입**: `TreeNode object`
- **설명**: 계층적 호출 트리의 루트

#### TreeNode 스키마

```typescript
interface TreeNode {
  // 필수 필드
  name: string;              // 노드 이름 (프레임/메서드명)
  sample_count: number;      // 이 노드의 총 샘플 수
  self_samples: number;      // 이 노드의 셀프 샘플 수
  percentage: number;        // 전체 샘플 중 백분율
  depth: number;             // 트리 깊이
  path: string[];            // 루트부터 현재 노드까지의 경로
  children: Record<string, TreeNode>;  // 자식 노드들

  // 선택적 필드
  thread_id?: number;        // 스레드 ID
  thread_name?: string;      // 스레드 이름
}
```

**TreeNode 필드 상세:**

| 필드 | 타입 | 설명 | 예시 |
|------|------|------|------|
| `name` | string | 노드 이름 | `"root"`, `"EpollEventLoop.run"` |
| `sample_count` | integer | 이 노드를 거친 총 샘플 | `112`, `45` |
| `self_samples` | integer | 이 노드에서 실행된 샘플 | `5`, `0` |
| `percentage` | number | 전체 샘플 중 비율 (%) | `99.94`, `25.5` |
| `depth` | integer | 트리 깊이 (루트=0) | `0`, `1`, `2` |
| `path` | string[] | 루트부터의 경로 | `["root", "EpollEventLoop.run"]` |
| `children` | object | 자식 노드 맵 | `{ "child1": {...}, "child2": {...} }` |
| `thread_id` | integer | 스레드 ID | `4006` |
| `thread_name` | string | 스레드 이름 | `"C2 CompilerThre"` |

### 스키마 타입 2: Hot Path (profile_type: "hot_path")

```json
{
  "type": "object",
  "profile_type": "hot_path",
  "properties": {
    "timestamp": { "type": "string" },
    "service": { "type": "string" },
    "profiler": { "type": "string" },
    "profiler_version": { "type": "string" },
    "profile_type": { "type": "string", "enum": ["hot_path"] },
    "path_name": { "type": "string" },
    "sample_count": { "type": "integer" },
    "self_samples": { "type": "integer" },
    "percentage": { "type": "number" },
    "depth": { "type": "integer" },
    "total_samples": { "type": "integer" },
    "path": { "type": "array", "items": { "type": "string" } },
    "thread_id": { "type": ["integer", "null"] },
    "thread_name": { "type": ["string", "null"] },
    "duration_ns": { "type": "integer" }
  },
  "required": ["timestamp", "service", "profiler", "profiler_version", "profile_type", "path_name", "sample_count", "self_samples", "percentage", "depth", "total_samples", "path", "duration_ns"]
}
```

#### Hot Path 필드 상세

##### 1-5. 기본 필드

Call Tree와 동일

##### 6. path_name (필수)

```json
"path_name": "IndexSet::lrg_union"
```

- **타입**: `string`
- **설명**: 핫 경로의 최상위 프레임 이름
- **의미**: CPU 시간을 소비하는 메서드/함수

##### 7. sample_count (필수)

```json
"sample_count": 3
```

- **타입**: `integer`
- **설명**: 이 경로의 총 샘플 수

##### 8. self_samples (필수)

```json
"self_samples": 3
```

- **타입**: `integer`
- **설명**: 이 경로에서 실제 실행된 샘플 수
- **의미**: CPU가 이 경로에서 실제로 실행된 시간
- **중요**: `self_samples`가 높을수록 핫 스팟

##### 9. percentage (필수)

```json
"percentage": 2.68
```

- **타입**: `number`
- **단위**: 백분율 (%)
- **설명**: 전체 샘플 중 이 경로가 차지하는 비율

##### 10. depth (필수)

```json
"depth": 3
```

- **타입**: `integer`
- **설명**: 호출 경로 깊이

##### 11. total_samples (필수)

```json
"total_samples": 112
```

- **타입**: `integer`
- **설명**: 전체 샘플 수 (백분율 계산용)

##### 12. path (필수)

```json
"path": [
  "IndexSet::lrg_union",
  "PhaseConservativeCoalesce::copy_copy",
  "PhaseConservativeCoalesce::coalesce"
]
```

- **타입**: `array` of `string`
- **설명**: 호출 경로 (상위 → 하위)
- **길이**: `depth`와 동일
- **제한**: 일반적으로 상위 30개 프레임

##### 13-14. 스레드 정보

```json
"thread_id": 4006,
"thread_name": "C2 CompilerThre"
```

- **타입**: `integer` 또는 `null`
- **설명**: 이 경로가 관측된 스레드

##### 15. duration_ns (필수) ⭐ v2 추가

```json
"duration_ns": 30000000
```

- **타입**: `integer`
- **단위**: 나노초 (ns)
- **설명**: 총 소요 시간
- **계산**: `sample_count × 10,000,000` (10ms 샘플링 기준)
- **예시**:
  - `10,000,000` = 10ms (1 sample)
  - `30,000,000` = 30ms (3 samples)
  - `300,000,000` = 300ms (30 samples)

**v2 추가 내용:**
- v1에서는 `duration_ns` 필드가 누락되어 있었음
- v2에서 모든 Hot Path 레코드에 `duration_ns`가 포함됨
- 실제 시간 기반 분석이 가능해짐

### 실제 예제

#### 예제 1: Call Tree Root

```json
{
  "timestamp": "2026-03-31T02:31:15.278384Z",
  "service": "server1",
  "profiler": "async-profiler",
  "profiler_version": "4.1",
  "profile_type": "call_tree",
  "total_samples": 112,
  "total_traces": 106,
  "unique_packages": 67,
  "unique_classes": 227,
  "java_packages": [
    "com.fasterxml.jackson.databind",
    "io.netty.channel",
    "reactor.core.publisher"
  ],
  "java_classes": [
    "AbstractChannelHandlerContext",
    "EpollEventLoop",
    "InternalMonoOperator"
  ],
  "call_tree": {
    "name": "root",
    "sample_count": 112,
    "self_samples": 0,
    "percentage": 99.94,
    "depth": 0,
    "path": [],
    "children": [],
    "thread_id": 4006,
    "thread_name": "C2 CompilerThre"
  }
}
```

#### 예제 2: Hot Path (JIT 컴파일)

```json
{
  "timestamp": "2026-03-31T02:31:15.278384Z",
  "service": "server1",
  "profiler": "async-profiler",
  "profiler_version": "4.1",
  "profile_type": "hot_path",
  "path_name": "IndexSet::lrg_union",
  "sample_count": 3,
  "self_samples": 3,
  "percentage": 2.68,
  "depth": 13,
  "total_samples": 112,
  "path": [
    "IndexSet::lrg_union",
    "PhaseConservativeCoalesce::copy_copy",
    "PhaseConservativeCoalesce::coalesce",
    "PhaseCoalesce::coalesce_driver",
    "PhaseChaitin::Register_Allocate",
    "Compile::Code_Gen",
    "Compile::Compile",
    "C2Compiler::compile_method",
    "CompileBroker::invoke_compiler_on_method",
    "CompileBroker::compiler_thread_loop",
    "JavaThread::thread_main_inner",
    "Thread::call_run",
    "thread_native_entry",
    "start_thread"
  ],
  "thread_id": 4006,
  "thread_name": "C2 CompilerThre",
  "duration_ns": 30000000
}
```

#### 예제 3: Hot Path (I/O 작업)

```json
{
  "timestamp": "2026-03-31T02:31:15.278384Z",
  "service": "server1",
  "profiler": "async-profiler",
  "profiler_version": "4.1",
  "profile_type": "hot_path",
  "path_name": "clock_gettime@@GLIBC_2.17",
  "sample_count": 3,
  "self_samples": 3,
  "percentage": 2.68,
  "depth": 2,
  "total_samples": 112,
  "path": [
    "clock_gettime@@GLIBC_2.17",
    "WatcherThread::sleep",
    "WatcherThread::run"
  ],
  "thread_id": 3999,
  "thread_name": "VM Periodic Tas",
  "duration_ns": 30000000
}
```

### 데이터 비교

| 특성 | stack-traces.jsonl | call-trees.jsonl |
|------|-------------------|-----------------|
| **용도** | 전체 스택 추적 | 핫 경로 식별 |
| **깊이** | 100+ 프레임 | 1-5 프레임 |
| **집계** | 개별 추적 | 집계된 트리 |
| **셀프 샘플** | 없음 | 있음 |
| **분석 초점** | "어떻게 여기 도달?" | "어디서 시간 소비?" |
| **duration_ns** | 선택적 | 필수 (Hot Path) |

---

## 3. 스키마 검증 도구

### v2 스키마 검증

```python
#!/usr/bin/env python3
import json
import sys

def validate_stack_trace_v2(record):
    """stack-traces.jsonl v2 레코드 검증"""
    required_fields = [
        'service', 'profiler', 'profiler_version', 'timestamp',
        'thread_id', 'thread_name', 'sample_count', 'stack_depth',
        'frame_types', 'stack'
    ]

    for field in required_fields:
        if field not in record:
            return False, f"Missing required field: {field}"

    # v2: 제거된 필드 확인 (존재하면 경고)
    deprecated_fields = [
        'top_frame', 'bottom_frame', 'has_kernel_frames',
        'has_java_frames', 'java_packages', 'java_classes'
    ]
    for field in deprecated_fields:
        if field in record:
            return False, f"Deprecated field in v2: {field} (use stack array or frame_types instead)"

    # 스택 프레임 검증
    if not isinstance(record['stack'], list):
        return False, "stack must be an array"

    if len(record['stack']) != record['stack_depth']:
        return False, "stack_depth doesn't match actual stack length"

    return True, "Valid v2"

def validate_call_tree(record):
    """call-trees.jsonl 레코드 검증"""
    if record['profile_type'] == 'call_tree':
        required_fields = [
            'timestamp', 'service', 'profiler', 'profiler_version',
            'profile_type', 'total_samples', 'total_traces',
            'unique_packages', 'unique_classes', 'java_packages',
            'java_classes', 'call_tree'
        ]
    elif record['profile_type'] == 'hot_path':
        required_fields = [
            'timestamp', 'service', 'profiler', 'profiler_version',
            'profile_type', 'path_name', 'sample_count', 'self_samples',
            'percentage', 'depth', 'total_samples', 'path', 'duration_ns'
        ]
    else:
        return False, f"Invalid profile_type: {record['profile_type']}"

    for field in required_fields:
        if field not in record:
            return False, f"Missing required field: {field}"

    return True, "Valid"
```

---

## 4. 사용 예제

### stack-traces.jsonl v2 읽기

```python
import json

with open('stack-traces.jsonl', 'r') as f:
    for line in f:
        trace = json.loads(line)

        # v2: 메타데이터 먼저 접근 (최적화된 순서)
        service = trace['service']
        thread_id = trace['thread_id']
        sample_count = trace['sample_count']

        # v2: 제거된 필드 대신 계산 사용
        has_kernel = trace['frame_types']['kernel'] > 0
        has_java = trace['frame_types']['java'] > 0

        # v2: stack 배열로 직접 접근
        top_frame = trace['stack'][0]
        bottom_frame = trace['stack'][-1]

        # v2: stack에서 패키지/클래스 추출
        packages = list(set(f.get('package') for f in trace['stack'] if f.get('package')))
        classes = list(set(f.get('class') for f in trace['stack'] if f.get('class')))

        # 핫 경로 필터링 (sample_count >= 10)
        if sample_count >= 10:
            print(f"Hot path: {trace['thread_name']}")
            print(f"  Samples: {sample_count}")
            print(f"  Depth: {trace['stack_depth']}")
```

### call-trees.jsonl 읽기 (v2 with duration_ns)

```python
import json

call_trees = []
hot_paths = []

with open('call-trees.jsonl', 'r') as f:
    for line in f:
        record = json.loads(line)

        if record['profile_type'] == 'call_tree':
            call_trees.append(record)
        elif record['profile_type'] == 'hot_path':
            hot_paths.append(record)

# v2: duration_ns를 사용한 핫 경로 분석
for path in sorted(hot_paths, key=lambda x: x['self_samples'], reverse=True)[:10]:
    duration_ms = path['duration_ns'] / 1_000_000  # 나노초 → 밀리초 변환
    print(f"{path['path_name']}: {path['self_samples']} self-samples, {duration_ms:.2f}ms")
```

---

## 5. 데이터 크기

| 파일 | 레코드 수 | 평균 크기 | 전체 크기 | 스키마 |
|------|----------|----------|----------|--------|
| stack-traces.jsonl | 100 | ~6.8KB | 681KB | v2 최적화 |
| call-trees.jsonl | 51 | ~550B | 30KB | v2 (duration_ns 추가) |

### v2 vs v1 비교

| 항목 | v1 | v2 | 개선 |
|------|----|----| ----|
| stack-traces 필드 수 | 16개 | 10개 | **-37.5%** |
| stack-traces 크기 | 720KB | 681KB | **-5.4%** |
| 중복 데이터 | 6개 필드 | 0개 | **완전 제거** |
| call-trees duration_ns | 없음 | 있음 | **기능 추가** |

---

## 6. 요약

### stack-traces.jsonl v2

- **목적**: 완전한 호출 스택 기록
- **특징**: 깊은 스택, 전체 경로
- **사용**: 코드 경로 분석, 디버깅
- **v2 개선**: 중복 제거, 필드 최적화

### call-trees.jsonl

- **목적**: 성능 병목 식별
- **특징**: 얕은 경로, 셀프 샘플
- **사용**: 핫 스팟 최적화
- **v2 개선**: duration_ns 추가

### v2 마이그레이션

**제거된 필드 대체:**
```python
# v1 → v2 변환
trace['top_frame']         → trace['stack'][0]
trace['bottom_frame']      → trace['stack'][-1]
trace['has_kernel_frames'] → trace['frame_types']['kernel'] > 0
trace['has_java_frames']   → trace['frame_types']['java'] > 0
trace['java_packages']     → [f['package'] for f in trace['stack'] if f.get('package')]
trace['java_classes']      → [f['class'] for f in trace['stack'] if f.get('class')]
```

---

**문서 버전:** 2.0
**최종 수정:** 2026-03-31
**상태:** ✅ v2 최적화 완료
