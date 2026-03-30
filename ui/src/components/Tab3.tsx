import React, { useState } from 'react';
import axios from 'axios';

type ArthasOperation = 'watch' | 'stack' | 'trace';

interface ArthasResult {
  jobId?: string;
  status?: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  results?: any[];
  value?: string;
}

interface LogEntry {
  time: string;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
}

const Tab3: React.FC = () => {
  const [operation, setOperation] = useState<ArthasOperation>('watch');
  const [className, setClassName] = useState('com.test.service.test1.controller.Test1Controller');
  const [methodName, setMethodName] = useState('test1');
  const [limit, setLimit] = useState(1);
  const [jobId, setJobId] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ArthasResult | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const API_BASE = 'http://localhost:8080';

  const addLog = (type: LogEntry['type'], message: string) => {
    const now = new Date().toLocaleTimeString('ko-KR', { hour12: false });
    setLogs(prev => [...prev, { time: now, type, message }]);
  };

  // Start Arthas operation
  const startOperation = async () => {
    setLoading(true);
    setResult(null);

    const endpoint = `${API_BASE}/api/arthas/start${operation.charAt(0).toUpperCase() + operation.slice(1)}`;

    try {
      const response = await axios.post(endpoint, {
        className,
        methodName,
        ...(operation === 'watch' && { limit })
      });

      if (response.data.success) {
        const newJobId = response.data.jobId;
        setJobId(newJobId);
        addLog('info', `${operation.toUpperCase()} job started: ${newJobId}`);
        addLog('info', '메서드 호출을 기다리는 중...');

        // Start polling for results
        pollForResult(newJobId);
      } else {
        addLog('error', `${operation.toUpperCase()} 시작 실패: ` + response.data.message);
        setLoading(false);
      }
    } catch (error: any) {
      addLog('error', `${operation.toUpperCase()} 시작 오류: ` + error.message);
      setLoading(false);
    }
  };

  // Poll for results
  const pollForResult = async (currentJobId: string) => {
    const endpoint = `${API_BASE}/api/arthas/get${operation.charAt(0).toUpperCase() + operation.slice(1)}Result/${currentJobId}`;

    const pollInterval = setInterval(async () => {
      try {
        const response = await axios.get(endpoint);
        const data = response.data;

        if (data.status === 'SUCCEEDED') {
          clearInterval(pollInterval);
          setLoading(false);
          setResult(data);
          addLog('success', `${operation.toUpperCase()} 완료!`);
        } else if (data.status === 'FAILED') {
          clearInterval(pollInterval);
          setLoading(false);
          setResult(data);
          addLog('error', `${operation.toUpperCase()} 실패`);
        }
        // Still RUNNING, continue polling
      } catch (error) {
        clearInterval(pollInterval);
        setLoading(false);
        addLog('error', '결과 조회 오류');
      }
    }, 2000);

    // Stop polling after 30 seconds
    setTimeout(() => clearInterval(pollInterval), 30000);
  };

  // Get operation name
  const getOperationName = (op: ArthasOperation): string => {
    switch (op) {
      case 'watch': return 'Watch';
      case 'stack': return 'Stack';
      case 'trace': return 'Trace';
    }
  };

  // Render result based on operation type
  const renderResult = () => {
    if (!result) return null;

    if (operation === 'watch') {
      return (
        <div className="space-y-4">
          <div className="text-sm">
            <span className="font-medium">Job ID:</span> {jobId}
          </div>
          <div className="text-sm">
            <span className="font-medium">상태:</span> {result.status === 'SUCCEEDED' ? 'COMPLETED' : result.status}
          </div>

          {result.results && result.results.length > 0 && (
            <div>
              <h4 className="text-sm font-medium text-black mb-2">캡처된 파라미터</h4>
              <div className="bg-black text-white p-4 rounded font-mono text-xs whitespace-pre-wrap">
                {result.results[0].value}
              </div>
            </div>
          )}
        </div>
      );
    }

    if (operation === 'stack') {
      return (
        <div className="space-y-4">
          <div className="text-sm">
            <span className="font-medium">Job ID:</span> {jobId}
          </div>
          <div className="text-sm">
            <span className="font-medium">상태:</span> {result.status === 'SUCCEEDED' ? 'COMPLETED' : result.status}
          </div>

          {result.results && result.results.length > 0 && (
            <div>
              <h4 className="text-sm font-medium text-black mb-2">호출 스택</h4>
              <div className="bg-black text-white p-4 rounded font-mono text-xs whitespace-pre-wrap">
                {result.results[0].value}
              </div>
            </div>
          )}
        </div>
      );
    }

    if (operation === 'trace') {
      return (
        <div className="space-y-4">
          <div className="text-sm">
            <span className="font-medium">Job ID:</span> {jobId}
          </div>
          <div className="text-sm">
            <span className="font-medium">상태:</span> {result.status === 'SUCCEEDED' ? 'COMPLETED' : result.status}
          </div>

          {result.results && result.results.length > 0 && (
            <div>
              <h4 className="text-sm font-medium text-black mb-2">호출 트리</h4>
              <div className="bg-black text-white p-4 rounded font-mono text-xs whitespace-pre-wrap">
                {result.results[0].value}
              </div>
            </div>
          )}
        </div>
      );
    }

    return null;
  };

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold text-black">Arthas 분석</h2>

      {/* 1. Operation Selection */}
      <div className="bg-white border border-black rounded-lg p-4">
        <div className="flex space-x-4">
          <button
            onClick={() => setOperation('watch')}
            className={`px-4 py-2 rounded text-sm font-medium border ${
              operation === 'watch'
                ? 'bg-black text-white border-black'
                : 'bg-white text-black border-black hover:bg-gray-200'
            }`}
          >
            Watch
          </button>
          <button
            onClick={() => setOperation('stack')}
            className={`px-4 py-2 rounded text-sm font-medium border ${
              operation === 'stack'
                ? 'bg-black text-white border-black'
                : 'bg-white text-black border-black hover:bg-gray-200'
            }`}
          >
            Stack
          </button>
          <button
            onClick={() => setOperation('trace')}
            className={`px-4 py-2 rounded text-sm font-medium border ${
              operation === 'trace'
                ? 'bg-black text-white border-black'
                : 'bg-white text-black border-black hover:bg-gray-200'
            }`}
          >
            Trace
          </button>
        </div>
      </div>

      {/* 2. Target Method */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">1. Target Method</h3>
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
        </div>
      </div>

      {/* 3. Operation Options */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">2. {getOperationName(operation)}: {operation === 'watch' ? '파라미터 값 추출' : operation === 'stack' ? '호출 스택 추적' : '메서드 내부 호출 트리'}</h3>

        {operation === 'watch' && (
          <div className="flex items-center gap-3">
            <label className="text-sm text-black">Limit:</label>
            <input
              type="number"
              className="border border-black rounded px-3 py-2 text-sm w-20"
              value={limit}
              onChange={(e) => setLimit(parseInt(e.target.value))}
              min={1}
              max={100}
            />
            <span className="text-sm text-black">회 캡처</span>
          </div>
        )}

        <div className="flex gap-3 mt-4">
          <button
            onClick={startOperation}
            disabled={loading}
            className="bg-black text-white px-6 py-2 rounded text-sm hover:bg-gray-800 disabled:bg-gray-200 disabled:text-gray-400"
          >
            {loading ? '실행 중...' : `${operation.charAt(0).toUpperCase() + operation.slice(1)} 시작`}
          </button>

          {jobId && !loading && (
            <button
              onClick={() => pollForResult(jobId)}
              className="bg-black text-white px-4 py-2 rounded text-sm hover:bg-gray-800"
            >
              결과 조회
            </button>
          )}
        </div>
      </div>

      {/* 4. Result Area */}
      <div className="bg-white border border-black rounded-lg p-4">
        <h3 className="text-sm font-medium text-black mb-3">3. 분석 결과</h3>

        {loading && (
          <div className="text-center py-8">
            <div className="text-4xl mb-4">•••</div>
            <p className="text-black">{operation.toUpperCase()} 실행 중...</p>
            <p className="text-sm text-black mt-2">Job ID: {jobId}</p>
            <p className="text-sm text-black mt-4">메서드 호출을 기다리는 중...</p>
            <div className="mt-4 p-3 bg-gray-200 rounded text-sm text-black">
              아래 명령어로 직접 호출하세요:<br/>
              <code className="text-xs">curl http://localhost:8081/{methodName}</code>
            </div>
          </div>
        )}

        {!loading && result && (
          <div className="bg-white border border-black p-4">
            {renderResult()}
          </div>
        )}

        {!loading && !result && (
          <div className="text-center py-8 text-black">
            {operation.toUpperCase()} 결과가 여기에 표시됩니다
          </div>
        )}
      </div>

      {/* 5. System Log */}
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
    </div>
  );
};

export default Tab3;
