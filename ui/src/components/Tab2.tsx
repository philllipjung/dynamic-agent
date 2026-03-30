import React, { useState } from 'react';
import axios from 'axios';

interface EventLog {
  timestamp: string;
  method: string;
  uri: string;
  headers: Record<string, string>;
  body: string;
}

interface LogEntry {
  time: string;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
}

const Tab2: React.FC = () => {
  const [serviceName, setServiceName] = useState('test1-service');
  const [pid, setPid] = useState('');
  const [capturing, setCapturing] = useState(false);
  const [events, setEvents] = useState<EventLog[]>([]);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [selectedEvent, setSelectedEvent] = useState<EventLog | null>(null);

  const API_BASE = 'http://localhost:8080';

  const addLog = (type: LogEntry['type'], message: string) => {
    const now = new Date().toLocaleTimeString('ko-KR', { hour12: false });
    setLogs(prev => [...prev, { time: now, type, message }]);
  };

  // Start Event Capturing
  const startCapturing = async () => {
    setCapturing(true);
    addLog('info', 'Event Capturing 시작...');

    try {
      const response = await axios.post(`${API_BASE}/api/bytebuddy/createEventAdvice`, {
        className: 'org.springframework.web.servlet.DispatcherServlet',
        methodName: 'doService'
      });

      if (response.data.success) {
        addLog('success', 'Event Advice 적용 완료');
        addLog('info', 'HTTP 요청 캡처 대기 중...');
        // Start polling for events
        startEventPolling();
      } else {
        addLog('error', 'Event Advice 적용 실패: ' + response.data.message);
        setCapturing(false);
      }
    } catch (error: any) {
      addLog('error', 'Event Advice 적용 오류: ' + error.message);
      setCapturing(false);
    }
  };

  // Stop Event Capturing
  const stopCapturing = () => {
    setCapturing(false);
    addLog('info', 'Event Capturing 중지');
  };

  // Poll for events (simulated - in real implementation, use WebSocket/SSE)
  const startEventPolling = () => {
    const pollInterval = setInterval(() => {
      if (!capturing) {
        clearInterval(pollInterval);
        return;
      }

      // Simulate event capture (replace with actual API call)
      // In real implementation, this would be WebSocket/SSE
      const mockEvent: EventLog = {
        timestamp: new Date().toISOString(),
        method: ['GET', 'POST', 'PUT', 'DELETE'][Math.floor(Math.random() * 4)],
        uri: '/api/test' + Math.floor(Math.random() * 100),
        headers: {
          'userId': 'user' + Math.floor(Math.random() * 1000),
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ test: 'data' })
      };

      setEvents(prev => [mockEvent, ...prev].slice(0, 50));
      addLog('info', `이벤트 캡처: ${mockEvent.method} ${mockEvent.uri}`);
    }, 5000);

    return () => clearInterval(pollInterval);
  };

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold text-black">Event Capturing 설정</h2>

      {/* 1. Target Service */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">1. Target Service</h3>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-xs text-black mb-1">Service</label>
            <select
              className="w-full border border-black rounded px-3 py-2 text-sm"
              value={serviceName}
              onChange={(e) => setServiceName(e.target.value)}
            >
              <option value="test1-service">test1-service</option>
              <option value="test2-service">test2-service</option>
            </select>
          </div>
          <div>
            <label className="block text-xs text-black mb-1">PID</label>
            <input
              type="text"
              className="w-full border border-black rounded px-3 py-2 text-sm"
              value={pid}
              onChange={(e) => setPid(e.target.value)}
              placeholder="자동 감지 또는 입력"
            />
          </div>
        </div>
      </div>

      {/* 2. Event Advice Configuration */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">2. Event Advice 적용</h3>
        <div className="space-y-3">
          <div>
            <label className="block text-xs text-black mb-1">Target Class (고정)</label>
            <input
              type="text"
              className="w-full border border-black rounded px-3 py-2 text-sm font-mono bg-gray-200"
              value="org.springframework.web.servlet.DispatcherServlet"
              disabled
            />
          </div>
          <div>
            <label className="block text-xs text-black mb-1">Target Method (고정)</label>
            <input
              type="text"
              className="w-full border border-black rounded px-3 py-2 text-sm font-mono bg-gray-200"
              value="doService"
              disabled
            />
          </div>
          <div className="text-xs text-black italic">
            모든 Spring Boot REST API에 동일하게 적용됩니다
          </div>
        </div>
      </div>

      {/* 3. Filter Options */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">3. 필터 옵션 (선택사항)</h3>
        <div className="space-y-3">
          <div className="flex items-center gap-3">
            <input type="checkbox" id="uriFilter" className="rounded" />
            <label htmlFor="uriFilter" className="text-sm text-black">URI 필터링</label>
            <input
              type="text"
              className="flex-1 border border-black rounded px-3 py-2 text-sm"
              placeholder="/api/**"
            />
          </div>
          <div className="flex items-center gap-3">
            <input type="checkbox" id="headerFilter" className="rounded" />
            <label htmlFor="headerFilter" className="text-sm text-black">헤더 필터링</label>
            <input
              type="text"
              className="border border-black rounded px-3 py-2 text-sm w-32"
              placeholder="userId"
            />
            <input
              type="text"
              className="flex-1 border border-black rounded px-3 py-2 text-sm"
              placeholder="값"
            />
          </div>
        </div>
      </div>

      {/* 4. Control Buttons */}
      <div className="flex gap-3">
        <button
          onClick={startCapturing}
          disabled={capturing || !pid}
          className="bg-black text-white px-6 py-2 rounded text-sm hover:bg-gray-800 disabled:bg-gray-200 disabled:text-gray-400"
        >
          {capturing ? '실행 중...' : 'Start Event Capturing'}
        </button>
        <button
          onClick={stopCapturing}
          disabled={!capturing}
          className="bg-black text-white px-4 py-2 rounded text-sm hover:bg-gray-800 disabled:bg-gray-200 disabled:text-gray-400"
        >
          Stop
        </button>
      </div>

      {/* 5. Event Logs */}
      <div className="bg-white border border-black rounded-lg p-4">
        <div className="flex justify-between items-center mb-3">
          <h3 className="text-sm font-medium text-black">Captured Events (최신 50개)</h3>
          <div className="flex gap-2">
            <button
              onClick={() => setEvents([])}
              className="text-xs text-black hover:underline"
            >
              모두 지우기
            </button>
            <button
              onClick={() => {
                const dataStr = JSON.stringify(events, null, 2);
                const dataBlob = new Blob([dataStr], { type: 'application/json' });
                const url = URL.createObjectURL(dataBlob);
                const link = document.createElement('a');
                link.href = url;
                link.download = 'events.json';
                link.click();
              }}
              className="text-xs text-black hover:underline"
            >
              내보내기(JSON)
            </button>
          </div>
        </div>

        <div className="space-y-2 max-h-96 overflow-y-auto">
          {events.length === 0 ? (
            <p className="text-black text-sm text-center py-8">
              {capturing ? '이벤트를 기다리는 중...' : '이벤트가 없습니다'}
            </p>
          ) : (
            events.map((event, index) => (
              <div key={index} className="bg-white border border-black rounded p-3">
                <div className="flex items-center gap-2 text-xs text-black mb-2">
                  <span>{new Date(event.timestamp).toLocaleString('ko-KR')}</span>
                  <span className="font-semibold text-black">{event.method}</span>
                </div>
                <div className="text-sm mb-2">
                  <span className="text-black">URI: </span>
                  <span className="font-mono">{event.uri}</span>
                </div>
                <div className="text-xs text-black mb-2">
                  <span className="font-medium">Headers:</span>
                  <div className="mt-1 space-y-1">
                    {Object.entries(event.headers).map(([key, value]) => (
                      <div key={key} className="pl-2">
                        <span className="text-black">{key}:</span> {value}
                      </div>
                    ))}
                  </div>
                </div>
                {event.body && (
                  <div className="text-xs text-black">
                    <span className="font-medium">Body:</span>
                    <div className="mt-1 p-2 bg-gray-200 rounded font-mono text-xs">
                      {event.body.length > 100 ? event.body.substring(0, 100) + '...' : event.body}
                    </div>
                  </div>
                )}
                <div className="flex gap-2 mt-2">
                  <button
                    onClick={() => setSelectedEvent(event)}
                    className="text-xs text-black hover:underline"
                  >
                    상세보기
                  </button>
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(JSON.stringify(event, null, 2));
                      addLog('info', '이벤트 복사됨');
                    }}
                    className="text-xs text-black hover:underline"
                  >
                    복사
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {/* 6. System Log */}
      <div className="bg-black text-white rounded-lg p-4 font-mono text-xs">
        <h3 className="text-sm font-bold mb-2">System Log</h3>
        <div className="space-y-1 max-h-40 overflow-y-auto">
          {logs.length === 0 ? (
            <p className="text-white">대기 중...</p>
          ) : (
            logs.map((log, i) => (
              <div key={i} className="flex gap-2">
                <span className="text-white">[{log.time}]</span>
                <span className="text-white">
                  [{log.type.toUpperCase()}] {log.message}
                </span>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Event Detail Modal */}
      {selectedEvent && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white border border-black rounded-lg p-6 max-w-2xl w-full">
            <h3 className="text-lg font-semibold mb-4">Event 상세정보</h3>

            <div className="space-y-4">
              <div>
                <h4 className="text-sm font-medium text-black mb-2">요청 정보</h4>
                <div className="bg-gray-200 rounded p-3 space-y-1 text-sm">
                  <div><span className="text-black">Timestamp:</span> {new Date(selectedEvent.timestamp).toLocaleString('ko-KR')}</div>
                  <div><span className="text-black">Method:</span> {selectedEvent.method}</div>
                  <div><span className="text-black">URI:</span> {selectedEvent.uri}</div>
                </div>
              </div>

              <div>
                <h4 className="text-sm font-medium text-black mb-2">Headers</h4>
                <div className="bg-gray-200 rounded p-3 max-h-32 overflow-y-auto">
                  {Object.entries(selectedEvent.headers).map(([key, value]) => (
                    <div key={key} className="text-sm py-1">
                      <span className="text-black">{key}:</span> {value}
                    </div>
                  ))}
                </div>
              </div>

              {selectedEvent.body && (
                <div>
                  <h4 className="text-sm font-medium text-black mb-2">Body</h4>
                  <pre className="bg-gray-200 rounded p-3 text-xs overflow-x-auto">
                    {JSON.stringify(JSON.parse(selectedEvent.body), null, 2)}
                  </pre>
                </div>
              )}
            </div>

            <div className="flex gap-2 mt-6">
              <button
                onClick={() => {
                  navigator.clipboard.writeText(JSON.stringify(selectedEvent, null, 2));
                  addLog('info', 'JSON 복사됨');
                }}
                className="bg-black text-white px-4 py-2 rounded text-sm hover:bg-gray-800"
              >
                JSON 복사
              </button>
              <button
                onClick={() => setSelectedEvent(null)}
                className="bg-white border border-black text-black px-4 py-2 rounded text-sm hover:bg-gray-200"
              >
                닫기
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Tab2;
