# Java Agent UI

React + TypeScript + TailwindCSS로 개발된 Java Agent 시스템 웹 UI

## 시작하기

### 사전 요구사항

- Node.js 16+
- npm 또는 yarn
- agent-server가 실행 중이어야 함 (http://localhost:8080)

### 설치 및 실행

```bash
# 의존성 설치
npm install

# 개발 모드 실행
npm start

# 빌드
npm run build

# 테스트
npm test
```

UI는 http://localhost:3000에서 실행됩니다.

## 기능

### 탭 1: Span & Link 생성

OpenTelemetry Span과 Span Link를 생성하여 분산 추적을 설정합니다.

**주요 기능:**
- 타겟 서비스 선택 (test1-service, test2-service)
- Arthas Watch로 파라미터 자동 감지
- Span Attribute instrumentation 적용
- Span Link 생성 (양쪽 서비스에 적용 필요)

**워크플로우:**
1. 타겟 서비스 (test2) 먼저 설정 및 적용
2. 소스 서비스 (test1) 설정 및 적용
3. test2 요청 → 10초 대기 → test1 요청 (링크 생성!)

### 탭 2: Event Capturing

HTTP 요청과 응답을 실시간으로 캡처합니다.

**주요 기능:**
- Spring DispatcherServlet에 Event Advice 적용
- 실시간 HTTP 요청/응답 캡처
- URI/헤더 필터링
- 이벤트 내보내기 (JSON)

### 탭 3: Arthas 분석

Alibaba Arthas를 사용한 런타임 분석 기능입니다.

**주요 기능:**
- **Watch**: 메서드 파라미터 값 추출
- **Stack**: 호출 스택 추적
- **Trace**: 메서드 내부 호출 트리 분석

## 프로젝트 구조

```
ui/
├── src/
│   ├── components/
│   │   ├── Tab1.tsx       # Span & Link 생성
│   │   ├── Tab2.tsx       # Event Capturing
│   │   └── Tab3.tsx       # Arthas 분석
│   ├── App.tsx            # 메인 앱 (탭 구조)
│   ├── App.css            # 스타일
│   └── index.css          # TailwindCSS 지시문
├── package.json
├── tsconfig.json
├── tailwind.config.js
└── README.md
```

## API 연동

모든 API는 agent-server (http://localhost:8080)와 통신합니다.

### 주요 엔드포인트

| 목적 | 엔드포인트 | 메서드 |
|------|-----------|--------|
| Span 생성 | /api/bytebuddy/createSpan | POST |
| Span Attribute 생성 | /api/bytebuddy/createSpanAttribute | POST |
| Event Advice 생성 | /api/bytebuddy/createEventAdvice | POST |
| Watch 시작 | /api/arthas/startWatch | POST |
| Watch 결과 | /api/arthas/getWatchResult/{jobId} | GET |
| Stack 시작 | /api/arthas/startStack | POST |
| Stack 결과 | /api/arthas/getStackResult/{jobId} | GET |
| Trace 시작 | /api/arthas/startTrace | POST |
| Trace 결과 | /api/arthas/getTraceResult/{jobId} | GET |

## 개발

### 컴포넌트 추가

```bash
# 새로운 컴포넌트 생성
mkdir src/components/NewFeature
touch src/components/NewFeature/index.tsx
```

### 스타일 커스터마이징

TailwindCSS 클래스를 사용하여 스타일을 적용합니다:
- `bg-blue-600` - 배경색
- `text-sm` - 텍스트 크기
- `rounded-lg` - 둥근 모서리
- `p-4` - 패딩

자세한 내용은 https://tailwindcss.com/docs 참조하세요.

## 문제 해결

### CORS 오류

agent-server의 CORS 설정을 확인하세요:
```java
@CrossOrigin(origins = "*")
```

### API 연결 실패

agent-server가 실행 중인지 확인:
```bash
curl http://localhost:8080/api/agent/jvms
```

### Port 3000 이미 사용 중

```bash
# Windows
netstat -ano | findstr :3000
taskkill /F /PID <PID>

# Linux/Mac
lsof -ti:3000 | xargs kill -9
```

## 라이선스

MIT

**저작권:** 2026 Java Agent System
