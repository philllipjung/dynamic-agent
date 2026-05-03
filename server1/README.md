# server1 Java 프로젝트

Spring WebFlux 기반 반응형 웹 애플리케이션입니다. async-profiler를 통한 상세한 Java 프로파일링 기능을 포함하고 있습니다.

## 프로젝트 위치

**전체 경로:** `/root/webflux-demo/server1`

## 프로젝트 개요

이 프로젝트는 다음 기술 스택을 사용합니다:

- **Spring Boot 3.2** - WebFlux 기반 반응형 웹 프레임워크
- **Project Reactor** - 반응형 프로그래밍 라이브러리
- **Netty** - 비동기 I/O 처리
- **async-profiler 4.1** - Java CPU 프로파일링 도구
- **OpenTelemetry** - 분산 추적 (Tracing)

## 폴더 구조

```
/root/webflux-demo/server1/
├── 📁 src/                              # Java 소스 코드
│   └── main/
│       ├── java/                        # Java 패키지
│       │   └── com/example/server1/
│       │       ├── Server1Application.java
│       │       └── controller/
│       └── resources/                   # 설정 파일
│           └── application.properties
│
├── 📁 gradle/                           # Gradle 래퍼 라이브러리
├── 📄 build.gradle                      # Gradle 빌드 설정
├── 📄 settings.gradle                   # Gradle 설정
├── 📄 gradlew                           # Gradle 실행 스크립트
│
├── 🐍 Python 프로파일링 스크립트
│   ├── async-profiler-enhanced.py       # collapsed → JSON 변환 (v2 최적화)
│   ├── build-call-tree.py                # traces → Call Tree 변환 (duration_ns 추가)
│   ├── extract-java-hotpaths.py         # Java hot paths 추출
│   ├── analyze_stack_traces.py          # 스택 추적 분석
│   ├── analyze_call_trees.py            # 호출 트리 분석
│   └── validate_json_schema.py          # JSON 스키마 검증 (v2)
│
├── 🚀 프로파일링 실행
│   └── run-with-detailed-profiling.sh   # 메인 프로파일링 스크립트
│
├── 📊 프로파일링 데이터 (v2 최적화)
│   ├── stack-traces.jsonl               # 스택 추적 (666KB, 100 레코드)
│   └── call-trees.jsonl                 # 호출 트리 (30KB, 51 레코드)
│
├── 📈 분석 보고서
│   ├── stack-traces-analysis-report.txt # 스택 추적 분석 (19KB)
│   └── call-trees-analysis-report.txt   # 호출 트리 분석 (15KB)
│
└── 📖 문서 (한국어)
    ├── README.md                        # 이 문서
    ├── JSON_SCHEMA_ANALYSIS.md          # JSON 스키마 상세 분석
    └── JSON_OPTIMIZATION_COMPLETE.md     # 최적화 내용
```

## 전체 파일 경로

### Python 스크립트
```
/root/webflux-demo/server1/async-profiler-enhanced.py
/root/webflux-demo/server1/build-call-tree.py
/root/webflux-demo/server1/analyze_stack_traces.py
/root/webflux-demo/server1/analyze_call_trees.py
/root/webflux-demo/server1/validate_json_schema.py
/root/webflux-demo/server1/extract-java-hotpaths.py
```

### 프로파일링 데이터
```
/root/webflux-demo/server1/stack-traces.jsonl
/root/webflux-demo/server1/call-trees.jsonl
```

### 분석 보고서
```
/root/webflux-demo/server1/stack-traces-analysis-report.txt
/root/webflux-demo/server1/call-trees-analysis-report.txt
```

### Java 프로젝트
```
/root/webflux-demo/server1/src/
/root/webflux-demo/server1/build.gradle
/root/webflux-demo/server1/settings.gradle
/root/webflux-demo/server1/gradlew
/root/webflux-demo/server1/gradle/
```

## 필수 구성 요소

### async-profiler 설치

```bash
cd /root
wget https://github.com/jvm-profiling-tools/async-profiler/releases/download/v4.1/async-profiler-4.1-linux-x64.tar.gz
tar -xzf async-profiler-4.1-linux-x64.tar.gz
```

### 애플리케이션 빌드

```bash
cd /root/webflux-demo/server1
./gradlew clean build
```

## 프로파일링 사용법

### 1. 애플리케이션 시작

```bash
cd /root/webflux-demo/server1
java -jar build/libs/server1-0.0.1-SNAPSHOT.jar
```

### 2. 프로파일링 시작

```bash
# 자동으로 server1 PID 감지
cd /root/webflux-demo/server1
./run-with-detailed-profiling.sh

# 또는 PID 직접 지정
./run-with-detailed-profiling.sh <PID>
```

### 3. 프로파일링 중지

```bash
# Ctrl+C로 정상 종료
# 또는
pkill -f run-with-detailed-profiling
```

## 프로파일링 데이터

### 출력 파일

1. **stack-traces.jsonl** - 상세한 스택 추적 정보 (최적화됨 v2)
   - 위치: `/root/webflux-demo/server1/stack-traces.jsonl`
   - 크기: 666KB
   - 레코드: 100개
   - 전체 호출 스택
   - 스레드 정보 (thread_id, thread_name)
   - 프레임 유형 분석 (kernel/java/native)
   - **v2 최적화 내용**:
     - `top_frame`, `bottom_frame` 제거
     - `has_kernel_frames`, `has_java_frames` 제거
     - 필드 순서 최적화 (메타데이터 → 큰 데이터)

2. **call-trees.jsonl** - 집계된 호출 트리
   - 위치: `/root/webflux-demo/server1/call-trees.jsonl`
   - 크기: 30KB
   - 레코드: 51개 (1개 루트 + 50개 핫 경로)
   - 계층적 트리 구조
   - 핫 경로 (Hot Paths)
   - 셀프 샘플 (실제 CPU 실행 위치)
   - **v2 추가 내용**:
     - `duration_ns` 추가 (Hot Path)

### 데이터 분석

#### 스택 추적 분석

```bash
cd /root/webflux-demo/server1

# 전체 분석 보고서 생성
python3 analyze_stack_traces.py stack-traces.jsonl

# 보고서 저장
python3 analyze_stack_traces.py stack-traces.jsonl > stack-traces-analysis-report.txt

# 주요 통계
- 총 추적 수: 100
- 총 샘플: 262
- 고유 스레드: 12개
- OpenTelemetry 오버헤드: 14.6%
```

#### 호출 트리 분석

```bash
cd /root/webflux-demo/server1

# 전체 분석 보고서 생성
python3 analyze_call_trees.py call-trees.jsonl

# 보고서 저장
python3 analyze_call_trees.py call-trees.jsonl > call-trees-analysis-report.txt

# 주요 통계
- 핫 경로: 50개
- 총 샘플: 112
- JIT 컴파일: 77% (웜업 단계)
- I/O 활동: 11.2%
```

## Python 스크립트 설명

### 변환 스크립트

| 파일 | 전체 경로 | 입력 | 출력 | 설명 |
|------|-----------|------|------|------|
| `async-profiler-enhanced.py` | `/root/webflux-demo/server1/async-profiler-enhanced.py` | collapsed | JSONL | 스택 프레임 향상, **v2 최적화됨** |
| `build-call-tree.py` | `/root/webflux-demo/server1/build-call-tree.py` | traces | JSONL | 계층적 호출 트리, **duration_ns 추가됨** |
| `extract-java-hotpaths.py` | `/root/webflux-demo/server1/extract-java-hotpaths.py` | collapsed | JSONL | Java hot paths 추출 |

### 분석 스크립트

| 파일 | 전체 경로 | 대상 | 출력 |
|------|-----------|------|------|
| `analyze_stack_traces.py` | `/root/webflux-demo/server1/analyze_stack_traces.py` | stack-traces.jsonl | 상세 분석 보고서 |
| `analyze_call_trees.py` | `/root/webflux-demo/server1/analyze_call_trees.py` | call-trees.jsonl | 상세 분석 보고서 |
| `validate_json_schema.py` | `/root/webflux-demo/server1/validate_json_schema.py` | *.jsonl | 스키마 검증 (v2) |

## JSON 스키마

### stack-traces.jsonl v2 (최적화됨)

**위치:** `/root/webflux-demo/server1/stack-traces.jsonl`

**제거된 필드:**
- `top_frame` → `stack[0]`로 대체
- `bottom_frame` → `stack[-1]`로 대체
- `has_kernel_frames` → `frame_types.kernel > 0`로 계산
- `has_java_frames` → `frame_types.java > 0`로 계산

```python
# 사용 예시
import json

with open('/root/webflux-demo/server1/stack-traces.jsonl', 'r') as f:
    for line in f:
        trace = json.loads(line)

        # 최적화된 필드 접근
        service = trace['service']
        thread_id = trace['thread_id']
        sample_count = trace['sample_count']

        # 제거된 필드는 계산으로 대체
        top_frame = trace['stack'][0]
        bottom_frame = trace['stack'][-1]
        has_kernel = trace['frame_types']['kernel'] > 0
```

### call-trees.jsonl (v2 수정)

**위치:** `/root/webflux-demo/server1/call-trees.jsonl`

**추가된 필드:**
- `duration_ns` - Hot Path에 추가됨

```json
{
  "path_name": "IndexSet::lrg_union",
  "sample_count": 3,
  "self_samples": 3,
  "depth": 13,
  "total_samples": 112,
  "path": [...],
  "duration_ns": 30000000  // ← 추가됨
}
```

## 주요 분석 결과

### 스택 추적 분석 결과

- **JIT 컴파일 활동**: 24.4% (steady-state)
- **I/O 바운드**: 37.7% (이벤트 루프)
- **OpenTelemetry 오버헤드**: 14.6%
- **스택 깊이**: 평균 100+ 프레임
- **GC 활동**: 낮음 (G1 GC, 12.5%)

### 호출 트리 분석 결과

- **JIT 컴파일**: 77% (웜업 단계)
- **핫 경로 깊이**: 1-5 프레임
- **셀프 샘플**: 레지스터 할당, 시스템 콜
- **I/O 활동**: 11.2%

## 최적화 내용

### 최적화된 스크립트

- **async-profiler-enhanced.py**: 중복 필드 제거
  - 제거: `top_frame`, `bottom_frame`, `has_*_frames`, `java_packages`, `java_classes`
  - 필드 순서 최적화: 메타데이터 → 큰 데이터
  - 전체 경로: `/root/webflux-demo/server1/async-profiler-enhanced.py`

- **build-call-tree.py**: duration_ns 추가
  - Hot Path에 `duration_ns` 필드 추가
  - `sample_count × 10,000,000`으로 계산
  - 전체 경로: `/root/webflux-demo/server1/build-call-tree.py`

### 크기 절감

```
stack-traces.jsonl: 720KB → 666KB (7.5% 절감)
  • 중복 필드 완전 제거
  • 필드 수: 16개 → 10개 (-37.5%)
```

## 성능 최적화 권장사항

### 1. OpenTelemetry 오버헤드 감소
- 현재: 14.6%
- 권장: 트레이스 샘플링 (10-50%)
- 기대 효과: 5-10% CPU 절감

### 2. JIT 웜업 모니터링
- 현재: 24% (steady-state), 77% (warmup)
- 권장: 10분 이상 부하 후 재프로파일링
- 기대 효과: 정상 상태의 실제 성능 파악

### 3. 반응형 연산자 체인 최적화
- 현재: 평균 100+ 프레임
- 권장: 연산자 체인 단순화
- 기대 효과: 지연 시간 감소

## 데이터 파일 크기

| 파일 | 전체 경로 | 레코드 수 | 크기 |
|------|-----------|----------|------|
| stack-traces.jsonl | `/root/webflux-demo/server1/stack-traces.jsonl` | 100 | 666KB |
| call-trees.jsonl | `/root/webflux-demo/server1/call-trees.jsonl` | 51 | 30KB |
| stack-traces-analysis-report.txt | `/root/webflux-demo/server1/stack-traces-analysis-report.txt` | - | 19KB |
| call-trees-analysis-report.txt | `/root/webflux-demo/server1/call-trees-analysis-report.txt` | - | 15KB |

## 개발 및 실행

### 애플리케이션 실행

```bash
# 작업 디렉토리 이동
cd /root/webflux-demo/server1

# 빌드
./gradlew clean build

# 실행
java -jar build/libs/server1-0.0.1-SNAPSHOT.jar

# 기본 포트: 8081
# 상태 확인: curl http://localhost:8081/actuator/health
```

### 프로파일링 실행

```bash
# 1단계: 애플리케이션 실행
cd /root/webflux-demo/server1
java -jar build/libs/server1-0.0.1-SNAPSHOT.jar &

# 2단계: 프로파일링 시작
./run-with-detailed-profiling.sh

# 3단계: 분석 (다른 터미널에서)
python3 analyze_stack_traces.py stack-traces.jsonl
python3 analyze_call_trees.py call-trees.jsonl
```

## 문서

- **README.md** (이 문서) - 프로젝트 개요 및 사용법
- **JSON_SCHEMA_ANALYSIS.md** - JSON 스키마 상세 분석
- **JSON_OPTIMIZATION_COMPLETE.md** - 최적화 내용

## 라이선스

이 프로젝트는 데모 목적으로 생성되었습니다.

---

**최종 수정:** 2026-03-31
**상태:** ✅ v2 최적화 완료
**위치:** `/root/webflux-demo/server1`
