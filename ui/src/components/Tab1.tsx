import React, { useState } from 'react';
import axios from 'axios';

interface ParamInfo {
  index: number;
  type: string;
  value: string;
  isUserInput: boolean;
  selected: boolean;
}

interface LogEntry {
  time: string;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
}

const Tab1: React.FC = () => {
  // Form state
  const [serviceName, setServiceName] = useState('test1-service');
  const [pid, setPid] = useState('');
  const [className, setClassName] = useState('com.test.service.test1.controller.Test1Controller');
  const [methodName, setMethodName] = useState('test1');
  const [instrumentationType, setInstrumentationType] = useState<'spanAttribute' | 'span'>('spanAttribute');
  const [parameters, setParameters] = useState<ParamInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [logs, setLogs] = useState<LogEntry[]>([]);

  // Watch dialog state
  const [showWatchDialog, setShowWatchDialog] = useState(false);
  const [watchLoading, setWatchLoading] = useState(false);
  const [watchJobId, setWatchJobId] = useState('');

  // API base URL
  const API_BASE = 'http://localhost:8080';

  const addLog = (type: LogEntry['type'], message: string) => {
    const now = new Date().toLocaleTimeString('ko-KR', { hour12: false });
    setLogs(prev => [...prev, { time: now, type, message }]);
  };

  // Start Watch job
  const startWatch = async () => {
    setWatchLoading(true);
    setShowWatchDialog(true);
    setParameters([]);

    try {
      const response = await axios.post(`${API_BASE}/api/arthas/startWatch`, {
        className,
        methodName,
        limit: 1
      });

      if (response.data.success) {
        setWatchJobId(response.data.jobId);
        addLog('info', `Watch job started: ${response.data.jobId}`);
        addLog('info', '메서드 호출을 기다리는 중...');

        // Start polling for results
        pollWatchResult(response.data.jobId);
      } else {
        addLog('error', 'Watch 시작 실패: ' + response.data.message);
        setWatchLoading(false);
      }
    } catch (error: any) {
      addLog('error', 'Watch 시작 오류: ' + error.message);
      setWatchLoading(false);
    }
  };

  // Poll Watch results
  const pollWatchResult = async (jobId: string) => {
    const pollInterval = setInterval(async () => {
      try {
        const response = await axios.get(`${API_BASE}/api/arthas/getWatchResult/${jobId}`);
        const data = response.data;

        if (data.status === 'SUCCEEDED' && data.results && data.results.length > 0) {
          clearInterval(pollInterval);
          setWatchLoading(false);
          parseWatchResults(data.results[0].value);
          addLog('success', '파라미터 감지 완료!');
        } else if (data.status === 'FAILED') {
          clearInterval(pollInterval);
          setWatchLoading(false);
          addLog('error', 'Watch 실패: ' + JSON.stringify(data));
        }
      } catch (error) {
        clearInterval(pollInterval);
        setWatchLoading(false);
        addLog('error', 'Watch 결과 조회 오류');
      }
    }, 2000);

    // Stop polling after 30 seconds
    setTimeout(() => clearInterval(pollInterval), 30000);
  };

  // Parse Watch results
  const parseWatchResults = (resultString: string) => {
    const newParams: ParamInfo[] = [];

    // Simple parsing - split by lines and extract parameter info
    const lines = resultString.split('\n');
    let paramIndex = 0;

    lines.forEach(line => {
      if (line.includes('param') || line.includes('@')) {
        const isUserInput = line.includes('"') && !line.includes('@RequestParam');
        const valueMatch = line.match(/"([^"]+)"/);
        const value = valueMatch ? valueMatch[1] : line.substring(0, 100);

        newParams.push({
          index: paramIndex++,
          type: 'Object',
          value: value,
          isUserInput,
          selected: isUserInput // Auto-select user input parameters
        });
      }
    });

    // If no parameters detected, add default
    if (newParams.length === 0) {
      newParams.push({
        index: 0,
        type: 'String',
        value: 'userId',
        isUserInput: true,
        selected: true
      });
    }

    setParameters(newParams);
  };

  // Apply instrumentation
  const applyInstrumentation = async () => {
    setLoading(true);

    try {
      const paramNames = parameters.filter(p => p.selected).map(p => p.value);

      const endpoint = instrumentationType === 'spanAttribute'
        ? `${API_BASE}/api/bytebuddy/createSpanAttribute`
        : `${API_BASE}/api/bytebuddy/createSpan`;

      const response = await axios.post(endpoint, {
        pid,
        className,
        methodName,
        ...(instrumentationType === 'spanAttribute' && { parameters: paramNames })
      });

      if (response.data.success) {
        addLog('success', response.data.message || 'Instrumentation applied successfully');
      } else {
        addLog('error', response.data.message || 'Instrumentation failed');
      }
    } catch (error: any) {
      addLog('error', 'Instrumentation 오류: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  // Toggle parameter selection
  const toggleParam = (index: number) => {
    setParameters(prev => prev.map((p, i) =>
      i === index ? { ...p, selected: !p.selected } : p
    ));
  };

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold text-black">Span & Link 생성</h2>

      {/* 1. Target Service Selection */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">1. Target Service 선택</h3>
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

      {/* 2. Source Method */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">2. Source Method (링크의 출발점)</h3>
        <div className="space-y-3">
          <div>
            <label className="block text-xs text-black mb-1">Class Name</label>
            <input
              type="text"
              className="w-full border border-black rounded px-3 py-2 text-sm font-mono"
              value={className}
              onChange={(e) => setClassName(e.target.value)}
              placeholder="com.example.MyClass"
            />
          </div>
          <div>
            <label className="block text-xs text-black mb-1">Method Name</label>
            <input
              type="text"
              className="w-full border border-black rounded px-3 py-2 text-sm font-mono"
              value={methodName}
              onChange={(e) => setMethodName(e.target.value)}
              placeholder="myMethod"
            />
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={startWatch}
              className="bg-black text-white px-4 py-2 rounded text-sm hover:bg-gray-800 disabled:bg-gray-200 disabled:text-gray-400"
              disabled={watchLoading}
            >
              {watchLoading ? '감지 중...' : 'Watch로 파라미터 확인'}
            </button>
            <span className="text-xs text-black">Arthas Watch로 파라미터 감지</span>
          </div>
        </div>
      </div>

      {/* 3. Link Attributes */}
      {parameters.length > 0 && (
        <div className="bg-white border border-black rounded-lg p-4">
          <h3 className="text-sm font-medium text-black mb-3">Link Attributes (선택된 속성)</h3>
          <div className="space-y-2">
            {parameters.map((param) => (
              <div key={param.index} className="flex items-start gap-3 p-3 bg-white rounded border border-black">
                <input
                  type="checkbox"
                  checked={param.selected}
                  onChange={() => toggleParam(param.index)}
                  className="mt-1"
                />
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium">param{param.index}</span>
                    <span className={`text-xs px-2 py-0.5 rounded border ${param.isUserInput ? 'bg-black text-white border-black' : 'bg-white text-black border-black'}`}>
                      {param.isUserInput ? '사용자 입력' : '프레임워크 주입'}
                    </span>
                  </div>
                  <div className="text-xs text-black mt-1">Type: {param.type}</div>
                  <div className="text-xs text-black mt-1">Value: {param.value}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 4. Instrumentation Type */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">4. Instrumentation Type</h3>
        <div className="space-x-4">
          <label className="inline-flex items-center">
            <input
              type="radio"
              value="spanAttribute"
              checked={instrumentationType === 'spanAttribute'}
              onChange={(e) => setInstrumentationType(e.target.value as any)}
              className="mr-2"
            />
            <span className="text-sm">spanAttribute (Span + 속성 + 링크)</span>
          </label>
          <label className="inline-flex items-center">
            <input
              type="radio"
              value="span"
              checked={instrumentationType === 'span'}
              onChange={(e) => setInstrumentationType(e.target.value as any)}
              className="mr-2"
            />
            <span className="text-sm">span (Span만 생성)</span>
          </label>
        </div>
      </div>

      {/* 5. Action Buttons */}
      <div className="flex gap-3">
        <button
          onClick={applyInstrumentation}
          disabled={loading || !pid}
          className="bg-black text-white px-6 py-2 rounded text-sm hover:bg-gray-800 disabled:bg-gray-200 disabled:text-gray-400"
        >
          {loading ? '적용 중...' : 'Apply Instrumentation'}
        </button>
        <button
          onClick={() => setLogs([])}
          className="bg-black text-white px-4 py-2 rounded text-sm hover:bg-gray-800"
        >
          로그 지우기
        </button>
      </div>

      {/* 6. Result Log */}
      <div className="bg-black text-white rounded-lg p-4 font-mono text-xs">
        <h3 className="text-sm font-bold mb-2">Result Log</h3>
        <div className="space-y-1 max-h-64 overflow-y-auto">
          {logs.length === 0 ? (
            <p className="text-gray-400">대기 중...</p>
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

      {/* Watch Dialog */}
      {showWatchDialog && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white border border-black rounded-lg p-6 max-w-2xl w-full">
            <h3 className="text-lg font-semibold mb-4">Arthas Watch - 파라미터 감지</h3>

            {watchLoading ? (
              <div className="text-center py-8">
                <div className="text-4xl mb-4">•••</div>
                <p className="text-black">Watch job started...</p>
                <p className="text-sm text-black mt-2">Job ID: {watchJobId}</p>
                <p className="text-sm text-black mt-4">메서드 호출을 기다리는 중...</p>
                <div className="mt-4 p-3 border border-black rounded text-sm text-black">
                  아래 명령어로 직접 호출하세요:<br/>
                  <code className="text-xs">curl http://localhost:8081/{serviceName === 'test1-service' ? 'test1' : 'test2'}</code>
                </div>
              </div>
            ) : (
              <div>
                <p className="text-sm text-black mb-4">감지된 파라미터 ({parameters.length}개)</p>
                <div className="space-y-2 max-h-64 overflow-y-auto mb-4">
                  {parameters.map((param) => (
                    <div key={param.index} className={`p-3 rounded border border-black ${param.isUserInput ? 'bg-black text-white' : 'bg-white text-black'}`}>
                      <div className="flex items-center gap-2 mb-2">
                        <span className="font-medium">param{param.index} (index: {param.index})</span>
                        <span className={`text-xs px-2 py-0.5 rounded border ${param.isUserInput ? 'bg-black text-white border-black' : 'bg-white text-black border-black'}`}>
                          {param.isUserInput ? '권장' : '확인 필요'}
                        </span>
                      </div>
                      <div className="text-xs text-black">Type: {param.type}</div>
                      <div className="text-xs text-black">Value: {param.value}</div>
                      {param.isUserInput ? (
                        <div className="text-xs text-black mt-1">사용자 입력 값 - Link 속성으로 사용 권장</div>
                      ) : (
                        <div className="text-xs text-black mt-1">프레임워크 주입 값 - 제외 권장</div>
                      )}
                    </div>
                  ))}
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => { setShowWatchDialog(false); startWatch(); }}
                    className="bg-black text-white px-4 py-2 rounded text-sm hover:bg-gray-800"
                  >
                    다시 감지
                  </button>
                  <button
                    onClick={() => setShowWatchDialog(false)}
                    className="bg-black text-white px-4 py-2 rounded text-sm hover:bg-gray-800"
                  >
                    선택 항목 적용
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default Tab1;
