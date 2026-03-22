// WebSocket connection
let ws = null;
let wsConnected = false;

// API base URL
const API_BASE = '/api';

// Initialize WebSocket
function initWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/agent`;

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        wsConnected = true;
        updateConnectionStatus(true);
        console.log('[WebSocket] Connected');
    };

    ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        handleWebSocketMessage(data);
    };

    ws.onclose = () => {
        wsConnected = false;
        updateConnectionStatus(false);
        console.log('[WebSocket] Disconnected');
        // Attempt to reconnect after 3 seconds
        setTimeout(initWebSocket, 3000);
    };

    ws.onerror = (error) => {
        console.error('[WebSocket] Error:', error);
        updateConnectionStatus(false);
    };
}

function updateConnectionStatus(connected) {
    const dot = document.getElementById('wsStatusDot');
    const text = document.getElementById('wsStatusText');

    if (connected) {
        dot.classList.add('connected');
        text.textContent = 'Connected';
    } else {
        dot.classList.remove('connected');
        text.textContent = 'Disconnected';
    }
}

function handleWebSocketMessage(data) {
    console.log('[WebSocket] Received:', data);

    switch (data.type) {
        case 'arthas_trace_response':
            appendOutput('traceOutput', data.message || '', data.success ? 'success' : 'error');
            break;
        case 'arthas_stack_response':
            appendOutput('stackOutput', data.message || '', data.success ? 'success' : 'error');
            break;
        case 'arthas_watch_response':
            appendOutput('watchOutput', data.message || '', data.success ? 'success' : 'error');
            break;
    }
}

function sendWebSocket(action, data) {
    if (wsConnected && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action, ...data }));
    } else {
        // Fallback to REST API
        executeViaRest(action, data);
    }
}

// REST API fallback
async function executeViaRest(action, data) {
    try {
        let endpoint, method, body;

        switch (action) {
            case 'arthas_trace':
                endpoint = `${API_BASE}/arthas/trace`;
                method = 'POST';
                body = JSON.stringify({
                    className: data.className,
                    methodName: data.methodName
                });
                break;
            case 'arthas_stack':
                endpoint = `${API_BASE}/arthas/stack`;
                method = 'POST';
                body = JSON.stringify({
                    className: data.className,
                    methodName: data.methodName
                });
                break;
            case 'arthas_watch':
                endpoint = `${API_BASE}/arthas/watch`;
                method = 'POST';
                body = JSON.stringify({
                    className: data.className,
                    methodName: data.methodName,
                    expression: data.expression || '{params, returnObj}',
                    limit: data.limit || 5
                });
                break;
            default:
                console.error('Unknown action:', action);
                return;
        }

        const response = await fetch(endpoint, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body
        });

        const result = await response.json();

        // Map to appropriate output area
        let outputId;
        switch (action) {
            case 'bytebuddy_log': outputId = 'logOutput'; break;
            case 'bytebuddy_span': outputId = 'spanOutput'; break;
            case 'arthas_trace': outputId = 'traceOutput'; break;
            case 'arthas_stack': outputId = 'stackOutput'; break;
            case 'arthas_watch': outputId = 'watchOutput'; break;
        }

        if (outputId) {
            // For watch command, the entire output is in message field
            const displayMessage = result.message || '';
            appendOutput(outputId, displayMessage, result.success ? 'success' : 'error');
        }
    } catch (error) {
        console.error('[REST API] Error:', error);
        appendOutput('logOutput', 'Error: ' + error.message, 'error');
    }
}

// Arthas: Trace
function traceMethod() {
    const className = document.getElementById('traceClassName').value.trim();
    const methodName = document.getElementById('traceMethodName').value.trim();

    if (!className || !methodName) {
        appendOutput('traceOutput', 'Error: Class name and Method name are required', 'error');
        return;
    }

    appendOutput('traceOutput', `Executing trace for ${className}.${methodName}...`, 'info');

    sendWebSocket('arthas_trace', {
        className,
        methodName
    });
}

// Arthas: Stack
function stackMethod() {
    const className = document.getElementById('stackClassName').value.trim();
    const methodName = document.getElementById('stackMethodName').value.trim();

    if (!className || !methodName) {
        appendOutput('stackOutput', 'Error: Class name and Method name are required', 'error');
        return;
    }

    appendOutput('stackOutput', `Getting stack for ${className}.${methodName}...`, 'info');

    sendWebSocket('arthas_stack', {
        className,
        methodName
    });
}

// Arthas: Watch
function watchMethod() {
    const className = document.getElementById('watchClassName').value.trim();
    const methodName = document.getElementById('watchMethodName').value.trim();
    const expression = document.getElementById('watchExpression').value.trim();
    const limit = document.getElementById('watchLimit').value || '5';

    if (!className || !methodName) {
        appendOutput('watchOutput', 'Error: Class name and Method name are required', 'error');
        return;
    }

    // Clear previous output
    const output = document.getElementById('watchOutput');
    output.innerHTML = '';

    // Add starting message
    appendOutput('watchOutput', `Watching ${className}.${methodName} with expression: ${expression} (limit: ${limit})...`, 'info');

    sendWebSocket('arthas_watch', {
        className,
        methodName,
        expression,
        limit: parseInt(limit)
    });
}

// Utility: Append output to area
function appendOutput(outputId, message, type = 'info') {
    const output = document.getElementById(outputId);

    // Clear "Output will appear here..." message
    if (output.innerHTML.includes('Output will appear here')) {
        output.innerHTML = '';
    }

    const line = document.createElement('div');
    line.className = `output-line output-${type}`;

    // Handle multi-line output (for watch results)
    if (message.includes('\n') && (message.includes('Affect') || message.includes('ts=') || message.includes('method=') || message.includes('location='))) {
        // This is a watch result with multiple lines - preserve formatting
        line.style.whiteSpace = 'pre-wrap';
        line.style.fontFamily = 'Consolas, Monaco, monospace';
        line.style.fontSize = '0.85em';
        line.style.lineHeight = '1.4';
        line.textContent = message;
    } else {
        line.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
    }

    output.appendChild(line);
    output.scrollTop = output.scrollHeight;
}

// Utility: Clear all outputs
function clearAllOutputs() {
    const outputIds = ['logOutput', 'spanOutput', 'traceOutput', 'stackOutput', 'watchOutput'];
    outputIds.forEach(id => {
        const output = document.getElementById(id);
        output.innerHTML = '<div class="output-line output-info">Output cleared.</div>';
    });
}

// ==================== DISCOVERY FUNCTIONS ====================

/**
 * Search for classes using Arthas sc command
 */
async function searchClasses() {
    const pattern = document.getElementById('classSearchPattern').value.trim();
    const outputId = 'classSearchResults';

    if (!pattern) {
        document.getElementById(outputId).innerHTML =
            '<div class="output-line output-error">Error: Please enter a class name pattern</div>';
        return;
    }

    document.getElementById(outputId).innerHTML =
        '<div class="output-line output-info">Searching for classes matching: ' + pattern + '...</div>';

    try {
        const response = await fetch(`${API_BASE}/arthas/sc?pattern=${encodeURIComponent(pattern)}`);
        const result = await response.json();

        if (result.success && result.message) {
            displayClassSearchResults(result.message, pattern);
        } else {
            document.getElementById(outputId).innerHTML =
                '<div class="output-line output-error">' + (result.message || 'No results found') + '</div>';
        }
    } catch (error) {
        document.getElementById(outputId).innerHTML =
            '<div class="output-line output-error">Error: ' + error.message + '</div>';
    }
}

/**
 * Display class search results with clickable items
 */
function displayClassSearchResults(message, pattern) {
    const outputId = 'classSearchResults';
    const output = document.getElementById(outputId);

    // Parse results - handle different response formats
    const results = parseClassSearchResults(message);

    if (results.length === 0) {
        output.innerHTML = '<div class="output-line output-info">No classes found matching: ' + pattern + '</div>';
        return;
    }

    let html = '<div style="margin-bottom: 10px;"><span class="output-success">Found ' + results.length + ' classes</span></div>';

    results.forEach(item => {
        html += `
            <div class="search-result-item clickable-result" onclick="selectClass('${item.className}')">
                <div class="search-result-name">${item.className}</div>
                <button class="use-button" onclick="event.stopPropagation(); useClassForAll('${item.className}')">Use All</button>
            </div>
        `;
    });

    output.innerHTML = html;
}

/**
 * Parse class search results from Arthas output
 */
function parseClassSearchResults(message) {
    const results = [];

    // Handle different formats
    // Format 1: "Found classes:\nPID xxx: processName"
    if (message.includes('Found classes:')) {
        // Extract from "PID" format
        const lines = message.split('\n');
        lines.forEach(line => {
            if (line.includes('PID ') && line.includes(':')) {
                const match = line.match(/PID\s+\d+:\s*(.+)/);
                if (match && match[1]) {
                    const classNames = match[1].trim().replace(/[\[\]]/g, '').split(',').map(s => s.trim()).filter(s => s);
                    classNames.forEach(className => {
                        if (className && !results.find(r => r.className === className)) {
                            results.push({ className, type: 'class' });
                        }
                    });
                }
            }
        });
    }

    // Format 2: Direct class listing
    const classPattern = /([a-zA-Z_$][a-zA-Z0-9_$]*(\.[a-zA-Z_$][a-zA-Z0-9_$]*)*)/g;
    const matches = message.match(classPattern);
    if (matches && matches.length > 0) {
        matches.forEach(className => {
            if (className.includes('.') && className.length > 5 && !results.find(r => r.className === className)) {
                results.push({ className, type: 'class' });
            }
        });
    }

    return results;
}

/**
 * Search for methods using Arthas sm command
 */
async function searchMethods() {
    const className = document.getElementById('methodSearchClass').value.trim();
    const pattern = document.getElementById('methodSearchPattern').value.trim() || '*';
    const outputId = 'methodSearchResults';

    if (!className) {
        document.getElementById(outputId).innerHTML =
            '<div class="output-line output-error">Error: Please enter a class name</div>';
        return;
    }

    document.getElementById(outputId).innerHTML =
        '<div class="output-line output-info">Searching methods in ' + className + ' matching: ' + pattern + '...</div>';

    try {
        const response = await fetch(
            `${API_BASE}/arthas/sm?className=${encodeURIComponent(className)}&methodPattern=${encodeURIComponent(pattern)}`
        );
        const result = await response.json();

        if (result.success) {
            displayMethodSearchResults(result.message, className);
        } else {
            document.getElementById(outputId).innerHTML =
                '<div class="output-line output-error">' + result.message + '</div>';
        }
    } catch (error) {
        document.getElementById(outputId).innerHTML =
            '<div class="output-line output-error">Error: ' + error.message + '</div>';
    }
}

/**
 * Display method search results
 */
function displayMethodSearchResults(message, className) {
    const outputId = 'methodSearchResults';
    const output = document.getElementById(outputId);

    const results = parseMethodSearchResults(message);

    if (results.length === 0) {
        output.innerHTML = '<div class="output-line output-info">No methods found in: ' + className + '</div>';
        return;
    }

    let html = '<div style="margin-bottom: 10px;"><span class="output-success">Found ' + results.length + ' methods</span></div>';

    // Group by method name
    const grouped = {};
    results.forEach(method => {
        if (method && method.name) {
            if (!grouped[method.name]) {
                grouped[method.name] = [];
            }
            grouped[method.name].push(method);
        }
    });

    Object.keys(grouped).sort().forEach(methodName => {
        const methods = grouped[methodName];
        const firstMethod = methods[0];

        html += `
            <div class="search-result-item clickable-result" onclick="selectMethod('${methodName}')">
                <div>
                    <div class="search-result-name">${methodName}</div>
                    <div class="search-result-meta">${firstMethod.signature || firstMethod.descriptor || ''}</div>
                </div>
                <button class="use-button" onclick="event.stopPropagation(); useMethodForAll('${className}', '${methodName}')">Use All</button>
            </div>
        `;
    });

    output.innerHTML = html;
}

/**
 * Parse method search results from Arthas output
 */
function parseMethodSearchResults(message) {
    const results = [];

    // Parse javap method output format:
    // "public int length();" or "private void methodName(Type1 arg1, Type2 arg2)"
    const lines = message.split('\n');
    lines.forEach(line => {
        line = line.trim();

        // Skip non-method lines
        if (!line || line.startsWith('Found ') || line.includes('Methods in') || line.includes('matching:')) {
            return;
        }

        // Match method declaration with parameters
        // Pattern: modifiers return_type method_name(params) throws...
        const methodMatch = line.match(/[\w\s\<\>\[\]\.]+\s+(\w+)\s*\(([^)]*)\)/);
        if (methodMatch) {
            const methodName = methodMatch[1];
            // Only add if it's a valid method name (not a class name)
            if (methodName && methodName.length > 0 &&
                methodName[0] !== methodName[0].toUpperCase() ||
                ['main', 'toString', 'hashCode', 'equals', 'length', 'getClass', 'wait', 'notify', 'notifyAll'].includes(methodName)) {
                results.push({
                    name: methodName,
                    signature: line.trim(),
                    descriptor: methodMatch[2] || ''
                });
            }
        }
    });

    // Remove duplicates
    const uniqueResults = [];
    const seen = new Set();
    results.forEach(result => {
        if (!seen.has(result.name + result.signature)) {
            seen.add(result.name + result.signature);
            uniqueResults.push(result);
        }
    });

    return uniqueResults;
}

/**
 * Select a class from search results
 */
function selectClass(className) {
    // Highlight the selected item (already done by click)
    // Could add visual feedback here
    console.log('Selected class:', className);
}

/**
 * Use class name for all input fields
 */
function useClassForAll(className) {
    const fields = [
        'logClassName', 'spanClassName',
        'traceClassName', 'stackClassName', 'watchClassName',
        'methodSearchClass'
    ];

    fields.forEach(fieldId => {
        const field = document.getElementById(fieldId);
        if (field) {
            field.value = className;
            // Visual feedback
            field.style.borderColor = '#00d9ff';
            setTimeout(() => {
                field.style.borderColor = '#333';
            }, 1000);
        }
    });
}

/**
 * Select a method from search results
 */
function selectMethod(methodName) {
    console.log('Selected method:', methodName);
}

/**
 * Use method name for all input fields
 */
function useMethodForAll(className, methodName) {
    const fields = [
        'logMethodName', 'spanMethodName',
        'traceMethodName', 'stackMethodName', 'watchMethodName'
    ];

    fields.forEach(fieldId => {
        const field = document.getElementById(fieldId);
        if (field) {
            field.value = methodName;
            // Visual feedback
            field.style.borderColor = '#00d9ff';
            setTimeout(() => {
                field.style.borderColor = '#333';
            }, 1000);
        }
    });

    // Also ensure class is set
    useClassForAll(className);
}

/**
 * Quick fill: Fill class and method for specific function
 */
function fillForFunction(functionType, className, methodName) {
    if (className) {
        const classField = document.getElementById(functionType + 'ClassName');
        if (classField) {
            classField.value = className;
        }
    }
    if (methodName) {
        const methodField = document.getElementById(functionType + 'MethodName');
        if (methodField) {
            methodField.value = methodName;
        }
    }
}

// ==================== ATTACH/DETACH FUNCTIONS ====================

/**
 * Show attach dialog with process list
 */
async function showAttachDialog() {
    const dialog = document.getElementById('attachDialog');
    dialog.style.display = 'flex';
    await refreshProcessList();
}

/**
 * Hide attach dialog
 */
function hideAttachDialog() {
    document.getElementById('attachDialog').style.display = 'none';
}

/**
 * Refresh Java process list
 */
async function refreshProcessList() {
    const processList = document.getElementById('processList');
    processList.innerHTML = '<div style="color: #888;">Loading processes...</div>';

    try {
        const response = await fetch(`${API_BASE}/arthas/processes`);
        const result = await response.json();

        if (result.processes && result.processes.length > 0) {
            let html = '';
            result.processes.forEach(proc => {
                const isAttached = result.attachedPids && result.attachedPids.includes(proc.pid);
                html += `
                    <div class="search-result-item" style="cursor: default;">
                        <div style="flex: 1;">
                            <div class="search-result-name">PID: ${proc.pid}</div>
                            <div class="search-result-meta">${proc.className}</div>
                        </div>
                        ${isAttached
                            ? '<button class="btn btn-secondary" disabled>Attached</button>'
                            : `<button class="btn btn-success" onclick="attachToPid('${proc.pid}')">Attach</button>`
                        }
                    </div>
                `;
            });
            processList.innerHTML = html;
        } else {
            processList.innerHTML = '<div style="color: #e94560;">No Java processes found</div>';
        }
    } catch (error) {
        processList.innerHTML = '<div style="color: #e94560;">Error: ' + error.message + '</div>';
    }
}

/**
 * Attach to PID
 */
async function attachToPid(pid) {
    try {
        const response = await fetch(`${API_BASE}/arthas/attach`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pid })
        });
        const result = await response.json();

        if (result.success) {
            appendOutput('classSearchResults', `Attached to PID ${pid}: ${result.message}`, 'success');
            hideAttachDialog();
            updateSessionCount();
        } else {
            appendOutput('classSearchResults', `Failed to attach: ${result.message}`, 'error');
        }
    } catch (error) {
        appendOutput('classSearchResults', `Error: ${error.message}`, 'error');
    }
}

/**
 * Show sessions dialog
 */
async function showSessionsDialog() {
    const dialog = document.getElementById('sessionsDialog');
    dialog.style.display = 'flex';
    await refreshSessions();
}

/**
 * Hide sessions dialog
 */
function hideSessionsDialog() {
    document.getElementById('sessionsDialog').style.display = 'none';
}

/**
 * Refresh active sessions
 */
async function refreshSessions() {
    const sessionsList = document.getElementById('sessionsList');
    sessionsList.innerHTML = '<div style="color: #888;">Loading sessions...</div>';

    try {
        const response = await fetch(`${API_BASE}/arthas/sessions`);
        const result = await response.json();

        if (result.sessions && Object.keys(result.sessions).length > 0) {
            let html = '';
            Object.entries(result.sessions).forEach(([pid, session]) => {
                const duration = Math.floor((Date.now() - session.startTime) / 1000);
                const minutes = Math.floor(duration / 60);
                const seconds = duration % 60;
                html += `
                    <div class="search-result-item">
                        <div style="flex: 1;">
                            <div class="search-result-name">PID: ${pid}</div>
                            <div class="search-result-meta">Session: ${session.sessionId} | Duration: ${minutes}m ${seconds}s</div>
                        </div>
                        <button class="btn btn-warning" onclick="detachFromPid('${pid}')">Detach</button>
                    </div>
                `;
            });
            sessionsList.innerHTML = html;
        } else {
            sessionsList.innerHTML = '<div style="color: #888;">No active sessions</div>';
        }
    } catch (error) {
        sessionsList.innerHTML = '<div style="color: #e94560;">Error: ' + error.message + '</div>';
    }
}

/**
 * Detach from PID
 */
async function detachFromPid(pid) {
    if (!confirm(`Detach from PID ${pid}?`)) {
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/arthas/detach?pid=${pid}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.success) {
            appendOutput('classSearchResults', `Detached from PID ${pid}`, 'success');
            refreshSessions();
            updateSessionCount();
        } else {
            appendOutput('classSearchResults', `Failed to detach: ${result.message}`, 'error');
        }
    } catch (error) {
        appendOutput('classSearchResults', `Error: ${error.message}`, 'error');
    }
}

/**
 * Reset all Arthas sessions
 * Detaches from all attached JVMs and clears session state
 */
async function resetAllSessions() {
    if (!confirm('Reset all Arthas sessions?\n\nThis will detach from all attached JVMs and clear all session state.\nContinue?')) {
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/arthas/reset`, {
            method: 'POST'
        });
        const result = await response.json();

        if (result.success) {
            alert('All Arthas sessions have been reset.\n\n' + result.message);
            refreshSessions();
            updateSessionCount();
        } else {
            alert('Failed to reset: ' + result.message);
        }
    } catch (error) {
        alert('Error: ' + error.message);
    }
}

/**
 * Select a method from search results and fill span creation form
 */
function selectMethod(methodName) {
    const className = document.getElementById('methodSearchClass').value.trim();
    if (className) {
        document.getElementById('spanClassName').value = className;
        document.getElementById('spanMethodName').value = methodName;
        appendOutput('spanOutput', `Selected: ${className}.${methodName}`, 'info');
    } else {
        appendOutput('spanOutput', 'Please search for a class first', 'error');
    }
}

/**
 * Create OpenTelemetry span for a method
 */
async function createSpan() {
    const className = document.getElementById('spanClassName').value.trim();
    const methodName = document.getElementById('spanMethodName').value.trim();

    if (!className || !methodName) {
        appendOutput('spanOutput', 'Error: Class name and Method name are required', 'error');
        return;
    }

    appendOutput('spanOutput', `Creating span for ${className}.${methodName}...`, 'info');

    try {
        // Get the first available PID (or use a specific PID if you have one)
        const processesResponse = await fetch(`${API_BASE}/arthas/processes`);
        const processesResult = await processesResponse.json();

        if (!processesResult.processes || processesResult.processes.length === 0) {
            appendOutput('spanOutput', 'Error: No Java processes found', 'error');
            return;
        }

        // Use the first non-agent-server process
        let targetPid = null;
        for (const proc of processesResult.processes) {
            if (!proc.className.includes('agent-server')) {
                targetPid = proc.pid;
                break;
            }
        }

        if (!targetPid) {
            // Fallback to first process
            targetPid = processesResult.processes[0].pid;
        }

        const response = await fetch(`${API_BASE}/bytebuddy/createSpan`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                pid: targetPid,
                className: className,
                methodName: methodName
            })
        });

        const result = await response.json();

        if (result.success) {
            appendOutput('spanOutput', result.message, 'success');
        } else {
            appendOutput('spanOutput', `Failed: ${result.message}`, 'error');
        }
    } catch (error) {
        appendOutput('spanOutput', `Error: ${error.message}`, 'error');
    }
}

/**
 * Update session count
 */
async function updateSessionCount() {
    try {
        const response = await fetch(`${API_BASE}/arthas/sessions`);
        const result = await response.json();
        const count = result.count || 0;
        document.getElementById('sessionCount').textContent = count;
    } catch (error) {
        console.error('Failed to update session count:', error);
    }
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    initWebSocket();
    updateSessionCount();
    // Update session count every 10 seconds
    setInterval(updateSessionCount, 10000);
});
