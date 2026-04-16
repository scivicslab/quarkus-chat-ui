// quarkus-chat-ui REST + EventSource SSE client

(function () {
    'use strict';

    // --- Login (multi-user mode) ---
    var currentUser = localStorage.getItem('chat-ui-user') || '';

    function showLogin() {
        document.getElementById('login-screen').style.display = 'flex';
        document.getElementById('app').style.display = 'none';
        var input = document.getElementById('login-user');
        if (input) { input.value = currentUser; input.focus(); }
    }

    function startApp(username) {
        currentUser = username;
        if (username) localStorage.setItem('chat-ui-user', username);
        document.getElementById('login-screen').style.display = 'none';
        document.getElementById('app').style.display = '';
        var userLabel = document.getElementById('user-label');
        if (userLabel) { userLabel.textContent = username; userLabel.style.display = username ? '' : 'none'; }
        var logoutBtn = document.getElementById('logout-btn');
        if (logoutBtn) logoutBtn.style.display = username ? '' : 'none';
        initApp();
    }

    document.getElementById('login-form').addEventListener('submit', function (e) {
        e.preventDefault();
        var username = document.getElementById('login-user').value.trim();
        if (username) startApp(username);
    });

    document.getElementById('logout-btn').addEventListener('click', function () {
        if (typeof eventSource !== 'undefined' && eventSource) { eventSource.close(); eventSource = null; }
        localStorage.removeItem('chat-ui-user');
        currentUser = '';
        window.location.reload();
    });

    // Check multi-user mode from server config
    fetch('api/config').then(function (r) { return r.json(); }).then(function (cfg) {
        if (cfg.multiUser) {
            if (currentUser) {
                startApp(currentUser);
            } else {
                showLogin();
            }
        } else {
            startApp('');
        }
    }).catch(function () {
        startApp('');
    });

    var appInitialized = false;

    function initApp() {
        if (appInitialized) return;
        appInitialized = true;
        doInitApp();
    }

    function apiUrl(path) {
        if (!currentUser) return path;
        return path + (path.indexOf('?') >= 0 ? '&' : '?') + 'user=' + encodeURIComponent(currentUser);
    }

    function doInitApp() {

    const chatArea = document.getElementById('chat-area');
    const promptInput = document.getElementById('prompt-input');
    const sendBtn = document.getElementById('send-btn');
    const queueBtn = document.getElementById('queue-btn');
    const cancelBtn = document.getElementById('cancel-btn');
    const modelSelect = document.getElementById('model-select');
    const themeSelect = document.getElementById('theme-select');
    var activeKeybind = 'default'; // set from /api/config
    const sessionLabel = document.getElementById('session-label');
    const connectionStatus = document.getElementById('connection-status');
    const queueArea = document.getElementById('queue-area');
    const queueResizeHandle = document.getElementById('queue-resize-handle');
    const inputResizeHandle = document.getElementById('input-resize-handle');
    const activityLabel = document.getElementById('activity-label');
    const inputArea = document.getElementById('input-area');
    const logPanel = document.getElementById('log-panel');
    const logContent = document.getElementById('log-content');

    let thinkingStartTime = null;   // Date.now() when thinking started
    let thinkingTimer = null;       // setInterval ID

    // --- Per-session localStorage isolation ---
    // When served under /session/{id}/ (k8s-pups), suffix localStorage keys with the
    // session ID so multiple instances don't share chat history, prompt queue, theme,
    // and model selection.  When served at / (standalone), suffix is empty — backward compatible.
    var SESSION_SUFFIX = (function () {
        var m = window.location.pathname.match(/^\/session\/([^/]+)\//);
        return m ? '-' + m[1] : '';
    })();

    // --- Theme (per-session in k8s-pups, global in standalone) ---
    var THEME_KEY = 'chat-ui-theme' + SESSION_SUFFIX;
    var savedTheme = localStorage.getItem(THEME_KEY) || 'dark-catppuccin';
    console.log('[chat-ui] theme restore: key=' + THEME_KEY + ' saved=' + localStorage.getItem(THEME_KEY) + ' using=' + savedTheme);
    document.documentElement.setAttribute('data-theme', savedTheme);
    themeSelect.value = savedTheme;

    themeSelect.addEventListener('change', function () {
        var theme = themeSelect.value;
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
        console.log('[chat-ui] theme saved: key=' + THEME_KEY + ' value=' + theme);
    });

    // --- Keybind (from server config: -Dchat-ui.keybind=emacs|vi|default) ---
    promptInput.addEventListener('keydown', function (e) {
        if (activeKeybind !== 'emacs') return;
        if (!e.ctrlKey) return;

        var ta = e.target;
        var start = ta.selectionStart;
        var end = ta.selectionEnd;
        var val = ta.value;

        switch (e.key) {
            case 'a': // beginning of line
                e.preventDefault();
                var lineStart = val.lastIndexOf('\n', start - 1) + 1;
                ta.setSelectionRange(lineStart, lineStart);
                break;
            case 'e': // end of line
                e.preventDefault();
                var lineEnd = val.indexOf('\n', start);
                if (lineEnd === -1) lineEnd = val.length;
                ta.setSelectionRange(lineEnd, lineEnd);
                break;
            case 'f': // forward char
                e.preventDefault();
                if (start < val.length) ta.setSelectionRange(start + 1, start + 1);
                break;
            case 'b': // backward char
                e.preventDefault();
                if (start > 0) ta.setSelectionRange(start - 1, start - 1);
                break;
            case 'n': // next line
                e.preventDefault();
                var curLineEndN = val.indexOf('\n', start);
                if (curLineEndN !== -1) {
                    var colN = start - (val.lastIndexOf('\n', start - 1) + 1);
                    var nextLineEnd = val.indexOf('\n', curLineEndN + 1);
                    if (nextLineEnd === -1) nextLineEnd = val.length;
                    var pos = Math.min(curLineEndN + 1 + colN, nextLineEnd);
                    ta.setSelectionRange(pos, pos);
                }
                break;
            case 'p': // previous line
                e.preventDefault();
                var curLineStartP = val.lastIndexOf('\n', start - 1) + 1;
                if (curLineStartP > 0) {
                    var colP = start - curLineStartP;
                    var prevLineStart = val.lastIndexOf('\n', curLineStartP - 2) + 1;
                    var pos2 = Math.min(prevLineStart + colP, curLineStartP - 1);
                    ta.setSelectionRange(pos2, pos2);
                }
                break;
            case 'd': // delete forward
                e.preventDefault();
                if (start < val.length) {
                    ta.value = val.substring(0, start) + val.substring(start + 1);
                    ta.setSelectionRange(start, start);
                }
                break;
            case 'h': // delete backward (backspace)
                e.preventDefault();
                if (start > 0) {
                    ta.value = val.substring(0, start - 1) + val.substring(start);
                    ta.setSelectionRange(start - 1, start - 1);
                }
                break;
            case 'k': // kill to end of line
                e.preventDefault();
                var killEnd = val.indexOf('\n', start);
                if (killEnd === -1) killEnd = val.length;
                if (killEnd === start && start < val.length) killEnd++; // kill newline if at end of line
                ta.value = val.substring(0, start) + val.substring(killEnd);
                ta.setSelectionRange(start, start);
                break;
        }
    });

    // --- Vi keybind ---
    var viMode = 'insert'; // 'normal' or 'insert'

    function updateViCursor(ta) {
        if (activeKeybind === 'vi' && viMode === 'normal') {
            ta.style.caretColor = 'transparent';
            // block cursor via box-shadow on a zero-width span is not possible in textarea,
            // use a visual indicator in placeholder instead
            ta.classList.add('vi-normal');
        } else {
            ta.style.caretColor = '';
            ta.classList.remove('vi-normal');
        }
    }

    promptInput.addEventListener('keydown', function (e) {
        if (activeKeybind !== 'vi') return;

        var ta = e.target;
        var start = ta.selectionStart;
        var end = ta.selectionEnd;
        var val = ta.value;

        if (viMode === 'normal') {
            e.preventDefault();
            switch (e.key) {
                case 'i': // insert mode
                    viMode = 'insert';
                    updateViCursor(ta);
                    break;
                case 'a': // append
                    viMode = 'insert';
                    if (start < val.length) ta.setSelectionRange(start + 1, start + 1);
                    updateViCursor(ta);
                    break;
                case 'A': // append at end of line
                    viMode = 'insert';
                    var eolA = val.indexOf('\n', start);
                    if (eolA === -1) eolA = val.length;
                    ta.setSelectionRange(eolA, eolA);
                    updateViCursor(ta);
                    break;
                case 'I': // insert at beginning of line
                    viMode = 'insert';
                    var bolI = val.lastIndexOf('\n', start - 1) + 1;
                    ta.setSelectionRange(bolI, bolI);
                    updateViCursor(ta);
                    break;
                case 'o': // open line below
                    viMode = 'insert';
                    var eolO = val.indexOf('\n', start);
                    if (eolO === -1) eolO = val.length;
                    ta.value = val.substring(0, eolO) + '\n' + val.substring(eolO);
                    ta.setSelectionRange(eolO + 1, eolO + 1);
                    updateViCursor(ta);
                    break;
                case 'h': // left
                    if (start > 0) ta.setSelectionRange(start - 1, start - 1);
                    break;
                case 'l': // right
                    if (start < val.length) ta.setSelectionRange(start + 1, start + 1);
                    break;
                case 'j': // down
                    var curEndJ = val.indexOf('\n', start);
                    if (curEndJ !== -1) {
                        var colJ = start - (val.lastIndexOf('\n', start - 1) + 1);
                        var nextEndJ = val.indexOf('\n', curEndJ + 1);
                        if (nextEndJ === -1) nextEndJ = val.length;
                        var posJ = Math.min(curEndJ + 1 + colJ, nextEndJ);
                        ta.setSelectionRange(posJ, posJ);
                    }
                    break;
                case 'k': // up
                    var curStartK = val.lastIndexOf('\n', start - 1) + 1;
                    if (curStartK > 0) {
                        var colK = start - curStartK;
                        var prevStartK = val.lastIndexOf('\n', curStartK - 2) + 1;
                        var posK = Math.min(prevStartK + colK, curStartK - 1);
                        ta.setSelectionRange(posK, posK);
                    }
                    break;
                case 'w': // next word
                    var mW = val.substring(start).match(/\S+\s*/);
                    if (mW) ta.setSelectionRange(start + mW[0].length, start + mW[0].length);
                    break;
                case 'b': // previous word
                    var before = val.substring(0, start);
                    var mB = before.match(/\S+\s*$/);
                    if (mB) ta.setSelectionRange(start - mB[0].length, start - mB[0].length);
                    break;
                case '0': // beginning of line
                    var bol0 = val.lastIndexOf('\n', start - 1) + 1;
                    ta.setSelectionRange(bol0, bol0);
                    break;
                case '$': // end of line
                    var eol$ = val.indexOf('\n', start);
                    if (eol$ === -1) eol$ = val.length;
                    ta.setSelectionRange(eol$, eol$);
                    break;
                case 'x': // delete char
                    if (start < val.length) {
                        ta.value = val.substring(0, start) + val.substring(start + 1);
                        ta.setSelectionRange(start, start);
                    }
                    break;
                case 'd': // dd = delete line (wait for second d)
                    // simplified: just delete current line
                    var bolD = val.lastIndexOf('\n', start - 1) + 1;
                    var eolD = val.indexOf('\n', start);
                    if (eolD === -1) eolD = val.length;
                    else eolD++; // include newline
                    ta.value = val.substring(0, bolD) + val.substring(eolD);
                    ta.setSelectionRange(bolD, bolD);
                    break;
                case 'g': // gg = top (simplified: single g goes to top)
                    ta.setSelectionRange(0, 0);
                    break;
                case 'G': // bottom
                    ta.setSelectionRange(val.length, val.length);
                    break;
            }
            return;
        }

        // insert mode: Escape to return to normal
        if (e.key === 'Escape') {
            e.preventDefault();
            viMode = 'normal';
            updateViCursor(ta);
        }
    });

    // --- Editable title ---
    var appTitle = document.getElementById('app-title');
    var defaultTitle = appTitle.textContent;
    var savedTitle = localStorage.getItem('chat-ui-custom-title');
    if (savedTitle) {
        appTitle.textContent = savedTitle;
        document.title = savedTitle;
    }
    appTitle.addEventListener('input', function () {
        document.title = appTitle.textContent || defaultTitle;
    });
    appTitle.addEventListener('blur', function () {
        var t = appTitle.textContent.trim();
        if (t && t !== defaultTitle) {
            localStorage.setItem('chat-ui-custom-title', t);
        } else {
            localStorage.removeItem('chat-ui-custom-title');
            appTitle.textContent = defaultTitle;
        }
        document.title = appTitle.textContent;
    });
    appTitle.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); appTitle.blur(); }
    });

    // --- Model key (per-session in k8s-pups, global in standalone) ---
    var MODEL_KEY = 'chat-ui-model' + SESSION_SUFFIX;

    let currentAssistantMsg = null;
    let currentAssistantText = '';
    let needsParagraphBreak = false; // insert \n\n before next delta (after tool use etc.)
    let busy = false;
    let pendingPrompt = false;  // true when an ask_user prompt is awaiting user response

    // --- Chat history (persisted to localStorage) ---
    var HISTORY_KEY = 'chat-ui-history' + SESSION_SUFFIX;
    var MAX_HISTORY = 500;
    var chatHistory = []; // [{role: 'user'|'assistant'|'info'|'error', text: string}]

    // --- Prompt Queue (position-based, persisted to localStorage) ---
    var QUEUE_KEY = 'chat-ui-queue' + SESSION_SUFFIX;
    var queue = [];   // [{text: string, auto: boolean}]
    var queuePos = 0; // index of next item to send
    var MAX_QUEUE_SIZE = 100;

    // Configure marked for safe rendering
    marked.setOptions({
        breaks: true,
        gfm: true
    });

    // Fix unclosed markdown fences/inline-code so marked.parse() doesn't break mid-stream
    function closeOpenMarkdown(text) {
        // Count triple-backtick fences
        var fenceCount = (text.match(/```/g) || []).length;
        if (fenceCount % 2 !== 0) {
            text += '\n```';
            return text; // inside an unclosed fence — single backtick count is irrelevant
        }
        // Count single backticks with complete fenced blocks removed first.
        // (Simple replace(/```/g,'') was wrong: it kept content inside fences,
        //  including any backticks inside them, which inflated the count.)
        var withoutFences = text.replace(/```[\s\S]*?```/g, '');
        var tickCount = (withoutFences.match(/`/g) || []).length;
        if (tickCount % 2 !== 0) {
            text += '`';
        }
        return text;
    }

    // Render the final (complete) text of an assistant message.
    // Does NOT call closeOpenMarkdown — the stream is done, the text is complete.
    function renderFinalMarkdown(text) {
        var displayText = text
            .replace(/<think>[\s\S]*?<\/think>/g, '')
            .replace(/<think>[\s\S]*$/, '');
        return marked.parse(displayText);
    }

    // --- Timestamp helper ---

    function formatTime(date) {
        var y = date.getFullYear();
        var m = String(date.getMonth() + 1).padStart(2, '0');
        var d = String(date.getDate()).padStart(2, '0');
        var hh = String(date.getHours()).padStart(2, '0');
        var mm = String(date.getMinutes()).padStart(2, '0');
        var ss = String(date.getSeconds()).padStart(2, '0');
        var tz = -date.getTimezoneOffset();
        var tzSign = tz >= 0 ? '+' : '-';
        var tzH = String(Math.floor(Math.abs(tz) / 60)).padStart(2, '0');
        var tzM = String(Math.abs(tz) % 60).padStart(2, '0');
        return y + '-' + m + '-' + d + 'T' + hh + ':' + mm + ':' + ss + tzSign + tzH + ':' + tzM;
    }

    // --- Model loading ---

    function loadModels() {
        fetch('api/models')
            .then(function (resp) { return resp.json(); })
            .then(function (models) {
                modelSelect.innerHTML = '';
                // Count distinct servers among local models
                var servers = {};
                models.forEach(function (m) {
                    if (m.type === 'local' && m.server) {
                        servers[m.server] = true;
                    }
                });
                var serverCount = Object.keys(servers).length;

                // Local models first, then Claude models
                var localModels = models.filter(function (m) { return m.type === 'local'; });
                var cloudModels = models.filter(function (m) { return m.type !== 'local'; });
                var sorted = localModels.concat(cloudModels);

                sorted.forEach(function (m) {
                    var opt = document.createElement('option');
                    opt.value = m.name;
                    opt.setAttribute('data-type', m.type);
                    if (m.type === 'local') {
                        opt.textContent = serverCount > 1 && m.server
                            ? m.name + ' (' + m.server + ')'
                            : m.name + ' (local)';
                    } else {
                        opt.textContent = m.name;
                    }
                    modelSelect.appendChild(opt);
                });

                // Restore previously selected model from localStorage
                var savedModel = localStorage.getItem(MODEL_KEY);
                console.log('[chat-ui] model restore: saved=' + savedModel + ' options=' + modelSelect.options.length);
                if (savedModel) {
                    var found = false;
                    for (var i = 0; i < modelSelect.options.length; i++) {
                        if (modelSelect.options[i].value === savedModel) {
                            modelSelect.value = savedModel;
                            found = true;
                            break;
                        }
                    }
                    console.log('[chat-ui] model restore: found=' + found + ' current=' + modelSelect.value);
                }
            })
            .catch(function () {
                modelSelect.innerHTML = '';
                var opt = document.createElement('option');
                opt.value = '';
                opt.textContent = '(no models available)';
                opt.disabled = true;
                modelSelect.appendChild(opt);
            });
    }

    // --- EventSource SSE connection ---

    var eventSource = null;

    function connectSSE() {
        if (eventSource) {
            eventSource.close();
        }
        eventSource = new EventSource(apiUrl('api/chat/stream'));

        eventSource.onopen = function () {
            connectionStatus.textContent = 'ready';
            connectionStatus.className = 'connected';
        };

        eventSource.onmessage = function (event) {
            try {
                handleEvent(JSON.parse(event.data));
            } catch (e) {
                // skip non-JSON
            }
        };

        eventSource.onerror = function () {
            connectionStatus.textContent = 'reconnecting';
            connectionStatus.className = 'disconnected';
        };
    }

    // --- Event handling ---

    function handleEvent(event) {
        switch (event.type) {
            case 'delta':
                handleDelta(event.content);
                break;
            case 'thinking':
                handleThinking(event.content);
                break;
            case 'result':
                handleResult(event);
                break;
            case 'error':
                appendMessage('error', event.content);
                break;
            case 'info':
                appendMessage('info', event.content);
                break;
            case 'mcp_user':
                appendMcpUserMessage(event.content);
                break;
            case 'btw_delta':
                handleBtwDelta(event.content);
                break;
            case 'btw_result':
                handleBtwResult();
                break;
            case 'status':
                updateStatus(event);
                break;
            case 'prompt':
                handlePrompt(event);
                break;
            case 'log':
                appendLog(event);
                break;
        }
    }

    function startThinkingTimer() {
        if (thinkingTimer) return; // already running
        thinkingStartTime = Date.now();
        thinkingTimer = setInterval(updateElapsed, 1000);
    }

    function stopThinkingTimer() {
        if (thinkingTimer) {
            clearInterval(thinkingTimer);
            thinkingTimer = null;
        }
        thinkingStartTime = null;
        activityLabel.textContent = '';
        activityLabel.removeAttribute('data-base');
    }

    function updateElapsed() {
        if (!thinkingStartTime) return;
        var elapsed = Math.floor((Date.now() - thinkingStartTime) / 1000);
        var suffix = ' (' + elapsed + 's)';
        // Update thinking indicator in chat area
        if (currentAssistantMsg) {
            var indicator = currentAssistantMsg.querySelector('.thinking-indicator');
            if (indicator) {
                var base = indicator.getAttribute('data-base') || indicator.textContent;
                if (!indicator.getAttribute('data-base')) indicator.setAttribute('data-base', base);
                indicator.textContent = base + suffix;
            }
        }
        // Update activity label in status bar
        var base = activityLabel.getAttribute('data-base');
        if (base) {
            activityLabel.textContent = base + suffix;
        }
    }

    function handleThinking(content) {
        // Re-enable cancel if server is still active (e.g., after POST timeout)
        if (!busy) {
            busy = true;
            cancelBtn.disabled = false;
        }
        if (!currentAssistantMsg) {
            currentAssistantMsg = document.createElement('div');
            currentAssistantMsg.className = 'message assistant streaming';
            chatArea.appendChild(currentAssistantMsg);
        }

        // All thinking content (tool use messages, reasoning) is shown temporarily.
        // Nothing is added to currentAssistantText — it all disappears when delta text arrives.
        if (content) {
            var label = content === 'Tool completed.' ? '✓ Tool completed.' : content;
            var indicator = currentAssistantMsg.querySelector('.thinking-indicator');
            if (indicator) {
                // Update existing indicator
                indicator.setAttribute('data-base', label);
                indicator.textContent = label;
            } else {
                // Create new indicator.
                // After a delta arrives, innerHTML= wipes the previous indicator.
                // We always recreate it so thinking events remain visible even mid-response.
                var ind = document.createElement('div');
                ind.className = 'thinking-indicator';
                ind.setAttribute('data-base', label);
                ind.textContent = label;
                currentAssistantMsg.appendChild(ind);
            }
            scrollToBottom();
        } else if (!currentAssistantText) {
            // No content yet: show generic "Thinking..." before first delta
            var indicator = document.createElement('div');
            indicator.className = 'thinking-indicator';
            indicator.textContent = 'Thinking...';
            currentAssistantMsg.appendChild(indicator);
            scrollToBottom();
        }
        // Update activity label in status bar
        var label = content || 'Thinking...';
        activityLabel.setAttribute('data-base', label);
        activityLabel.textContent = label;
        startThinkingTimer();
    }

    function handleDelta(content) {
        if (!content) {
            return;
        }

        // Re-enable cancel if server is still active (e.g., after POST timeout)
        if (!busy) {
            busy = true;
            cancelBtn.disabled = false;
        }

        if (!currentAssistantMsg) {
            currentAssistantMsg = document.createElement('div');
            currentAssistantMsg.className = 'message assistant streaming';
            chatArea.appendChild(currentAssistantMsg);
        }

        if (needsParagraphBreak) {
            needsParagraphBreak = false;
            if (currentAssistantText && !currentAssistantText.endsWith('\n\n')) {
                currentAssistantText += '\n\n';
            }
        }

        currentAssistantText += content;
        // Strip <think>...</think> blocks (Qwen3 thinking mode) from display
        var displayText = currentAssistantText
            .replace(/<think>[\s\S]*?<\/think>/g, '')
            .replace(/<think>[\s\S]*$/, '');  // partial unclosed <think> block
        currentAssistantMsg.innerHTML = marked.parse(closeOpenMarkdown(displayText));
        scrollToBottom();
    }

    function handleResult(msg) {
        stopThinkingTimer();
        if (currentAssistantMsg) {
            currentAssistantMsg.classList.remove('streaming');
            // Re-render with the complete text — no closeOpenMarkdown patching needed.
            if (currentAssistantText) {
                currentAssistantMsg.innerHTML = renderFinalMarkdown(currentAssistantText);
            }

            var footer = document.createElement('div');
            footer.className = 'message-footer';


            if (msg.costUsd != null && msg.costUsd > 0) {
                var cost = document.createElement('span');
                cost.textContent = 'Cost: $' + msg.costUsd.toFixed(4);
                footer.appendChild(cost);
            }
            if (msg.durationMs != null && msg.durationMs >= 0) {
                var duration = document.createElement('span');
                var secs = (msg.durationMs / 1000).toFixed(1);
                duration.textContent = 'Duration: ' + secs + 's';
                footer.appendChild(duration);
            }
            if (msg.sessionId) {
                var session = document.createElement('span');
                session.textContent = 'Session: ' + msg.sessionId.substring(0, 12) + '...';
                session.title = msg.sessionId;
                footer.appendChild(session);
            }

            // Model name
            var modelName = modelSelect.value || '';
            if (modelName) {
                var modelSpan = document.createElement('span');
                modelSpan.title = modelName;
                modelSpan.textContent = modelName.length > 30
                    ? modelName.substring(0, 30) + '...' : modelName;
                footer.appendChild(modelSpan);
            }

            // Copy as Markdown button
            var copyBtn = document.createElement('button');
            copyBtn.className = 'copy-md-btn';
            copyBtn.textContent = 'Copy MD';
            copyBtn.title = 'Copy as Markdown';
            var mdText = currentAssistantText;
            copyBtn.addEventListener('click', function () {
                navigator.clipboard.writeText(mdText).then(function () {
                    copyBtn.textContent = 'Copied!';
                    setTimeout(function () { copyBtn.textContent = 'Copy MD'; }, 1500);
                });
            });
            footer.appendChild(copyBtn);

            var timeSpan = document.createElement('span');
            timeSpan.textContent = formatTime(new Date());
            footer.appendChild(timeSpan);

            currentAssistantMsg.appendChild(footer);

            currentAssistantMsg = null;
            chatHistory.push({ role: 'assistant', text: currentAssistantText });
            currentAssistantText = '';
            needsParagraphBreak = false;
            saveHistory();
            scrollToBottom();
            trimChatArea();
        }

        // result event is the authoritative signal that a turn is complete.
        // Update status from result event (model, session, busy) and process queue.
        if (msg.model) {
            updateStatus(msg);
        }
        if (msg.busy === false) {
            busy = false;
            cancelBtn.disabled = true;
            promptInput.focus();
            processQueue();
        }
    }

    function handlePrompt(event) {
        var div = document.createElement('div');
        div.className = 'message prompt';
        var contentP = document.createElement('p');
        contentP.textContent = event.content || 'Prompt from Claude';
        div.appendChild(contentP);

        // All prompts (ask_user, permission, etc.) are sent via /api/respond
        // which writes to Claude CLI's stdin. The CLI process is still running
        // and waiting for the response in its read loop.

        if (event.options && event.options.length > 0) {
            var btnGroup = document.createElement('div');
            btnGroup.className = 'prompt-buttons';
            event.options.forEach(function (opt) {
                var btn = document.createElement('button');
                btn.className = 'prompt-option-btn';
                btn.textContent = opt;
                btn.addEventListener('click', function () {
                    pendingPrompt = false;
                    sendResponse(event.promptId, opt);
                    div.classList.add('answered');
                    btnGroup.querySelectorAll('button').forEach(
                        function (b) { b.disabled = true; });
                });
                btnGroup.appendChild(btn);
            });
            div.appendChild(btnGroup);
        } else {
            // Free-text input for the response
            var inputRow = document.createElement('div');
            inputRow.className = 'prompt-input-row';
            var input = document.createElement('input');
            input.type = 'text';
            input.className = 'prompt-text-input';
            input.placeholder = 'Type your response...';
            var submitBtn = document.createElement('button');
            submitBtn.className = 'prompt-option-btn';
            submitBtn.textContent = 'Send';
            submitBtn.addEventListener('click', function () {
                if (input.value.trim()) {
                    pendingPrompt = false;
                    sendResponse(event.promptId, input.value.trim());
                    div.classList.add('answered');
                    input.disabled = true;
                    submitBtn.disabled = true;
                }
            });
            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && !e.isComposing) {
                    e.preventDefault();
                    submitBtn.click();
                }
            });
            inputRow.appendChild(input);
            inputRow.appendChild(submitBtn);
            div.appendChild(inputRow);
        }

        chatArea.appendChild(div);
        chatHistory.push({ role: 'prompt', text: event.content || '' });
        saveHistory();
        scrollToBottom();
    }

    // Send text as a regular chat message (for ask_user prompt responses)
    function sendPromptText(text) {
        queue.splice(queuePos, 0, { text: text, auto: true });
        trimQueue();
        renderQueue();
        saveQueue();
        if (!busy) {
            processQueue();
        } else {
            showQueue();
        }
    }

    async function sendResponse(promptId, response) {
        try {
            await fetch(apiUrl('api/respond'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ promptId: promptId, response: response })
            });
            chatHistory.push({ role: 'user', text: '[Response] ' + response });
            saveHistory();
        } catch (e) {
            appendMessage('error', 'Failed to send response: ' + e.message);
        }
    }

    function updateStatus(msg) {
        if (msg.model) {
            // Restore from localStorage first; fall back to server-side model
            var savedModel = localStorage.getItem(MODEL_KEY);
            var targetModel = savedModel || msg.model;
            var currentOption = modelSelect.options[modelSelect.selectedIndex];
            var currentIsLocal = currentOption && currentOption.getAttribute('data-type') === 'local';
            console.log('[chat-ui] updateStatus model: server=' + msg.model + ' saved=' + savedModel + ' target=' + targetModel + ' currentIsLocal=' + currentIsLocal);
            if (!currentIsLocal) {
                // Only set if the target model exists in the dropdown
                for (var i = 0; i < modelSelect.options.length; i++) {
                    if (modelSelect.options[i].value === targetModel) {
                        modelSelect.value = targetModel;
                        break;
                    }
                }
            }
        }
        if (msg.sessionId) {
            sessionLabel.textContent = 'Session: ' + msg.sessionId.substring(0, 12) + '...';
            sessionLabel.title = msg.sessionId;
        } else {
            sessionLabel.textContent = '';
        }
        if (msg.busy != null) {
            busy = msg.busy;
            cancelBtn.disabled = !busy;
            if (!busy) {
                stopThinkingTimer();
                promptInput.focus();
                // Safety net: if result event doesn't arrive (e.g., error path),
                // process queue after a longer delay to avoid being stuck.
                // Normal path: handleResult() calls processQueue() immediately.
                setTimeout(function () {
                    if (!busy) processQueue();
                }, 2000);
            }
        }
    }

    var notificationBar = document.getElementById('notification-bar');
    function isTaskNotification(text) {
        return text && text.indexOf('<task-notification>') !== -1;
    }
    function showNotification(text) {
        var item = document.createElement('div');
        item.className = 'notification-item';
        var statusMatch = text.match(/<status>(.*?)<\/status>/);
        var summaryMatch = text.match(/<summary>(.*?)<\/summary>/);
        var status = statusMatch ? statusMatch[1] : 'unknown';
        var summary = summaryMatch ? summaryMatch[1] : 'Background task notification';
        item.textContent = '[' + status + '] ' + summary;
        item.className += ' notification-' + status;
        notificationBar.appendChild(item);
        notificationBar.style.display = 'block';
        setTimeout(function () {
            item.classList.add('notification-fade');
            setTimeout(function () {
                if (item.parentNode) item.parentNode.removeChild(item);
                if (notificationBar.children.length === 0) notificationBar.style.display = 'none';
            }, 500);
        }, 10000);
    }

    function appendMessage(className, text) {
        if (className === 'user' && isTaskNotification(text)) {
            showNotification(text);
            return;
        }
        var div = document.createElement('div');
        div.className = 'message ' + className;
        div.textContent = text;
        if (className === 'user') {
            var footer = document.createElement('div');
            footer.className = 'message-footer';
            var timeSpan = document.createElement('span');
            timeSpan.textContent = formatTime(new Date());
            footer.appendChild(timeSpan);
            var copyBtn = document.createElement('button');
            copyBtn.className = 'copy-md-btn';
            copyBtn.textContent = 'Copy';
            copyBtn.title = 'Copy prompt text';
            var promptText = text;
            copyBtn.addEventListener('click', function () {
                navigator.clipboard.writeText(promptText).then(function () {
                    copyBtn.textContent = 'Copied!';
                    setTimeout(function () { copyBtn.textContent = 'Copy'; }, 1500);
                });
            });
            footer.appendChild(copyBtn);
            div.appendChild(footer);
        }
        chatArea.appendChild(div);
        chatHistory.push({ role: className, text: text });
        saveHistory();
        scrollToBottom();
        trimChatArea();
    }

    function appendMcpUserMessage(text) {
        var div = document.createElement('div');
        div.className = 'message user mcp-user';
        div.textContent = text;
        var footer = document.createElement('div');
        footer.className = 'message-footer';
        var timeSpan = document.createElement('span');
        timeSpan.textContent = formatTime(new Date());
        footer.appendChild(timeSpan);
        div.appendChild(footer);
        chatArea.appendChild(div);
        chatHistory.push({ role: 'user', text: text });
        saveHistory();
        scrollToBottom();
        trimChatArea();
    }

    // --- BTW side question ---

    var btwResponseText = '';

    function showBtwOverlay(question) {
        btwResponseText = '';
        document.getElementById('btw-question').textContent = question;
        document.getElementById('btw-response').innerHTML =
            '<span class="thinking-indicator">Thinking...</span>';
        document.getElementById('btw-overlay').style.display = 'flex';
    }

    function handleBtwDelta(content) {
        if (!content) return;
        btwResponseText += content;
        document.getElementById('btw-response').innerHTML =
            marked.parse(closeOpenMarkdown(btwResponseText));
    }

    function handleBtwResult() {
        if (btwResponseText) {
            document.getElementById('btw-response').innerHTML =
                marked.parse(btwResponseText);
        }
    }

    async function executeBtw(question) {
        showBtwOverlay(question);
        try {
            var resp = await fetch(apiUrl('api/btw'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ question: question, model: modelSelect.value })
            });
            if (!resp.ok) {
                document.getElementById('btw-response').textContent =
                    'Error: HTTP ' + resp.status;
            }
        } catch (e) {
            document.getElementById('btw-response').textContent = 'Error: ' + e.message;
        }
    }

    // --- History persistence (localStorage) ---

    function saveHistory() {
        var toSave = chatHistory.slice(-MAX_HISTORY);
        try {
            localStorage.setItem(HISTORY_KEY, JSON.stringify(toSave));
        } catch (e) {
            // localStorage full — trim aggressively
            try {
                localStorage.setItem(HISTORY_KEY, JSON.stringify(chatHistory.slice(-50)));
            } catch (e2) { /* give up */ }
        }
    }

    function restoreHistory() {
        try {
            var saved = localStorage.getItem(HISTORY_KEY);
            if (!saved) return;
            var entries = JSON.parse(saved);
            if (!Array.isArray(entries) || entries.length === 0) return;

            chatHistory = entries.filter(function (e) { return !(e.role === 'user' && isTaskNotification(e.text)); });
            for (var i = 0; i < entries.length; i++) {
                var entry = entries[i];
                if (entry.role === 'assistant') {
                    chatArea.appendChild(createAssistantDiv(entry.text));
                } else if (entry.role === 'prompt') {
                    var div = document.createElement('div');
                    div.className = 'message prompt answered';
                    var p = document.createElement('p');
                    p.textContent = entry.text;
                    div.appendChild(p);
                    chatArea.appendChild(div);
                } else {
                    var div = document.createElement('div');
                    div.className = 'message ' + entry.role;
                    div.textContent = entry.text;
                    if (entry.role === 'user') {
                        var footer = document.createElement('div');
                        footer.className = 'message-footer';
                        var copyBtn = document.createElement('button');
                        copyBtn.className = 'copy-md-btn';
                        copyBtn.textContent = 'Copy';
                        copyBtn.title = 'Copy prompt text';
                        (function(t, btn) {
                            btn.addEventListener('click', function () {
                                navigator.clipboard.writeText(t).then(function () {
                                    btn.textContent = 'Copied!';
                                    setTimeout(function () { btn.textContent = 'Copy'; }, 1500);
                                });
                            });
                        })(entry.text, copyBtn);
                        footer.appendChild(copyBtn);
                        div.appendChild(footer);
                    }
                    chatArea.appendChild(div);
                }
            }
            scrollToBottom();
        } catch (e) {
            // ignore restore errors
        }
    }

    // --- Queue persistence (localStorage) ---

    function saveQueue() {
        try {
            localStorage.setItem(QUEUE_KEY, JSON.stringify({ queue: queue, pos: queuePos }));
        } catch (e) { /* ignore */ }
    }

    function restoreQueue() {
        try {
            var saved = localStorage.getItem(QUEUE_KEY);
            if (!saved) return;
            var data = JSON.parse(saved);
            if (data && Array.isArray(data.queue)) {
                queue = data.queue;
                queuePos = typeof data.pos === 'number' ? data.pos : 0;
                if (queuePos > queue.length) queuePos = queue.length;
                if (queue.length > 0) {
                    showQueue();
                    renderQueue();
                }
            }
        } catch (e) { /* ignore */ }
    }

    function createAssistantDiv(text) {
        var div = document.createElement('div');
        div.className = 'message assistant';
        div.innerHTML = marked.parse(text);

        var footer = document.createElement('div');
        footer.className = 'message-footer';

        var copyBtn = document.createElement('button');
        copyBtn.className = 'copy-md-btn';
        copyBtn.textContent = 'Copy MD';
        copyBtn.title = 'Copy as Markdown';
        copyBtn.addEventListener('click', function () {
            navigator.clipboard.writeText(text).then(function () {
                copyBtn.textContent = 'Copied!';
                setTimeout(function () { copyBtn.textContent = 'Copy MD'; }, 1500);
            });
        });
        footer.appendChild(copyBtn);
        div.appendChild(footer);
        return div;
    }

    var userScrolledUp = false;

    chatArea.addEventListener('scroll', function () {
        var threshold = 80;
        var atBottom = chatArea.scrollHeight - chatArea.scrollTop - chatArea.clientHeight < threshold;
        userScrolledUp = !atBottom;
    });

    function scrollToBottom() {
        if (!userScrolledUp) {
            chatArea.scrollTop = chatArea.scrollHeight;
        }
    }

    function forceScrollToBottom() {
        userScrolledUp = false;
        chatArea.scrollTop = chatArea.scrollHeight;
    }

    // --- Chat area memory limit ---
    var MAX_CHAT_LINES = 5000;

    function trimChatArea() {
        if (chatArea.children.length < 3) return;
        var totalLines = chatArea.innerText.split('\n').length;
        while (totalLines > MAX_CHAT_LINES && chatArea.children.length > 1) {
            var oldest = chatArea.children[0];
            var oldestLines = (oldest.innerText || '').split('\n').length;
            chatArea.removeChild(oldest);
            totalLines -= oldestLines;
        }
    }

    // --- Queue management (position-based) ---

    function addToQueue(text) {
        queue.push({ text: text, auto: true });
        trimQueue();
        renderQueue();
        saveQueue();
    }

    function trimQueue() {
        while (queue.length > MAX_QUEUE_SIZE) {
            queue.shift();
            if (queuePos > 0) queuePos--;
        }
    }

    function removeFromQueue(index) {
        queue.splice(index, 1);
        if (index < queuePos) {
            queuePos--;
        } else if (index === queuePos && queuePos >= queue.length) {
            // pos was pointing at removed item which was last
        }
        renderQueue();
        saveQueue();
    }

    function toggleAutoInQueue(index) {
        if (index >= 0 && index < queue.length) {
            queue[index].auto = !queue[index].auto;
            renderQueue();
            saveQueue();
        }
    }

    function moveInQueue(index, direction) {
        var target = index + direction;
        if (index < queuePos || target < queuePos || target >= queue.length) return;
        var tmp = queue[index];
        queue[index] = queue[target];
        queue[target] = tmp;
        renderQueue();
        saveQueue();
    }

    function hasPending() {
        return queuePos < queue.length;
    }

    function renderQueue() {
        if (queue.length === 0) {
            queueArea.innerHTML = '<div class="queue-header"><span>Queue is empty</span></div>';
            return;
        }

        var pending = queue.length - queuePos;
        var headerText = 'Queue (' + queue.length + ')';
        if (pending > 0) {
            headerText += ' - ' + pending + ' pending';
        }
        headerText += ':';

        var html = '<div class="queue-header">'
            + '<span>' + escapeHtml(headerText) + '</span>'
            + '<button class="queue-save-btn" id="queue-save-btn" title="Save as Markdown">Save</button>'
            + '</div>';

        for (var i = 0; i < queue.length; i++) {
            var item = queue[i];
            var displayText = item.text;
            var sent = (i < queuePos);
            var isCurrent = (i === queuePos);
            var isWaiting = (isCurrent && !busy && !item.auto);

            var cls = 'queue-item';
            if (sent) cls += ' sent';
            if (isCurrent) cls += ' current';
            if (isWaiting) cls += ' waiting';

            var checked = item.auto ? ' checked' : '';

            html += '<div class="' + cls + '" data-index="' + i + '">'
                + '<span class="queue-index">' + (i + 1) + '.</span>'
                + '<span class="queue-text" title="' + escapeAttr(item.text) + '">'
                + escapeHtml(displayText) + '</span>';

            if (!sent) {
                html += '<label class="queue-auto">'
                    + '<input type="checkbox"' + checked + ' data-queue-auto="' + i + '"> Auto</label>';
                var canUp = (i > queuePos);
                var canDown = (i < queue.length - 1);
                html += '<button class="queue-move" data-queue-up="' + i + '"'
                    + (canUp ? '' : ' disabled') + ' title="Move up">&uarr;</button>';
                html += '<button class="queue-move" data-queue-down="' + i + '"'
                    + (canDown ? '' : ' disabled') + ' title="Move down">&darr;</button>';
            }

            html += '<button class="queue-edit" data-queue-edit="' + i + '" title="Edit (copy to input)">📝</button>';
            html += '<button class="queue-remove" data-queue-remove="' + i + '" title="Remove">&times;</button>'
                + '</div>';
        }

        queueArea.innerHTML = html;
        queueArea.scrollTop = queueArea.scrollHeight;
    }

    // Delegate click events on queue area
    queueArea.addEventListener('click', function (e) {
        var editBtn = e.target.closest('[data-queue-edit]');
        if (editBtn) {
            var idx = parseInt(editBtn.getAttribute('data-queue-edit'), 10);
            var item = queue[idx];
            if (item) {
                promptInput.value = item.text;
                autoResize();
                promptInput.focus();
            }
            return;
        }

        var removeBtn = e.target.closest('[data-queue-remove]');
        if (removeBtn) {
            var idx = parseInt(removeBtn.getAttribute('data-queue-remove'), 10);
            removeFromQueue(idx);
            return;
        }

        var upBtn = e.target.closest('[data-queue-up]');
        if (upBtn) {
            var idx = parseInt(upBtn.getAttribute('data-queue-up'), 10);
            moveInQueue(idx, -1);
            return;
        }

        var downBtn = e.target.closest('[data-queue-down]');
        if (downBtn) {
            var idx = parseInt(downBtn.getAttribute('data-queue-down'), 10);
            moveInQueue(idx, 1);
            return;
        }

        if (e.target.id === 'queue-save-btn' || e.target.closest('#queue-save-btn')) {
            saveQueueAsMarkdown();
            return;
        }
    });

    queueArea.addEventListener('change', function (e) {
        if (e.target.hasAttribute('data-queue-auto')) {
            var idx = parseInt(e.target.getAttribute('data-queue-auto'), 10);
            toggleAutoInQueue(idx);
        }
    });

    // --- Input textarea resize handle ---
    var INPUT_HEIGHT_KEY = 'chat-ui-input-height' + SESSION_SUFFIX;
    var savedInputHeight = localStorage.getItem(INPUT_HEIGHT_KEY);
    if (savedInputHeight) {
        promptInput.style.height = savedInputHeight + 'px';
    } else {
        // Default: match rows="5" (line-height:1.5 * 14px * 5 + 2*10px padding)
        promptInput.style.height = '125px';
    }

    (function () {
        var dragging = false;
        var startY = 0;
        var startHeight = 0;

        inputResizeHandle.addEventListener('mousedown', function (e) {
            e.preventDefault();
            dragging = true;
            startY = e.clientY;
            startHeight = promptInput.offsetHeight;
            inputResizeHandle.classList.add('dragging');
            document.body.style.cursor = 'ns-resize';
            document.body.style.userSelect = 'none';
        });

        document.addEventListener('mousemove', function (e) {
            if (!dragging) return;
            var delta = startY - e.clientY;
            var newHeight = Math.max(42, Math.min(startHeight + delta, 500));
            promptInput.style.height = newHeight + 'px';
        });

        document.addEventListener('mouseup', function () {
            if (!dragging) return;
            dragging = false;
            inputResizeHandle.classList.remove('dragging');
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            localStorage.setItem(INPUT_HEIGHT_KEY, promptInput.offsetHeight);
        });
    })();

    // --- Queue resize handle ---
    var QUEUE_HEIGHT_KEY = 'chat-ui-queue-height' + SESSION_SUFFIX;
    var savedQueueHeight = localStorage.getItem(QUEUE_HEIGHT_KEY);
    if (savedQueueHeight) {
        queueArea.style.height = savedQueueHeight + 'px';
    } else {
        queueArea.style.height = '130px';
    }

    (function () {
        var dragging = false;
        var startY = 0;
        var startHeight = 0;

        queueResizeHandle.addEventListener('mousedown', function (e) {
            e.preventDefault();
            dragging = true;
            startY = e.clientY;
            startHeight = queueArea.offsetHeight;
            queueResizeHandle.classList.add('dragging');
            document.body.style.cursor = 'ns-resize';
            document.body.style.userSelect = 'none';
        });

        document.addEventListener('mousemove', function (e) {
            if (!dragging) return;
            var delta = startY - e.clientY;
            var newHeight = Math.max(60, Math.min(startHeight + delta, 400));
            queueArea.style.height = newHeight + 'px';
        });

        document.addEventListener('mouseup', function () {
            if (!dragging) return;
            dragging = false;
            queueResizeHandle.classList.remove('dragging');
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            localStorage.setItem(QUEUE_HEIGHT_KEY, queueArea.offsetHeight);
        });
    })();

    function saveQueueAsMarkdown() {
        if (queue.length === 0) return;

        var lines = ['# Prompt Queue', ''];
        for (var i = 0; i < queue.length; i++) {
            var item = queue[i];
            var marker = (i < queuePos) ? '[x]' : '[ ]';
            lines.push((i + 1) + '. ' + marker + ' ' + item.text);
        }
        lines.push('');

        var blob = new Blob([lines.join('\n')], { type: 'text/markdown' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        var now = new Date();
        var ts = now.getFullYear()
            + String(now.getMonth() + 1).padStart(2, '0')
            + String(now.getDate()).padStart(2, '0')
            + '-' + String(now.getHours()).padStart(2, '0')
            + String(now.getMinutes()).padStart(2, '0');
        a.download = 'prompt-queue-' + ts + '.md';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    function saveChatAsMarkdown() {
        if (chatHistory.length === 0) return;

        var now = new Date();
        var dateStr = now.getFullYear() + '-'
            + String(now.getMonth() + 1).padStart(2, '0') + '-'
            + String(now.getDate()).padStart(2, '0') + ' '
            + String(now.getHours()).padStart(2, '0') + ':'
            + String(now.getMinutes()).padStart(2, '0');

        var lines = ['# Conversation - ' + dateStr, ''];

        for (var i = 0; i < chatHistory.length; i++) {
            var entry = chatHistory[i];
            if (entry.role === 'user') {
                lines.push('## User', '', entry.text, '');
            } else if (entry.role === 'assistant') {
                lines.push('## Assistant', '', entry.text, '');
            } else if (entry.role === 'info') {
                lines.push('> [info] ' + entry.text, '');
            } else if (entry.role === 'error') {
                lines.push('> [error] ' + entry.text, '');
            } else if (entry.role === 'prompt') {
                lines.push('> [prompt] ' + entry.text, '');
            }
        }

        var blob = new Blob([lines.join('\n')], { type: 'text/markdown' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        var ts = now.getFullYear()
            + String(now.getMonth() + 1).padStart(2, '0')
            + String(now.getDate()).padStart(2, '0')
            + String(now.getHours()).padStart(2, '0')
            + String(now.getMinutes()).padStart(2, '0');
        a.download = 'conversation-' + ts + '.md';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    function escapeHtml(str) {
        var div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function escapeAttr(str) {
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
                  .replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function processQueue() {
        if (pendingPrompt) return;
        if (!hasPending()) return;

        if (queue[queuePos].auto) {
            sendFromQueue();
        } else {
            renderQueue();
        }
    }

    function sendFromQueue() {
        if (!hasPending()) return;
        var item = queue[queuePos];
        queuePos++;
        renderQueue();
        saveQueue();
        executePrompt(item.text);
    }

    // --- Send prompt ---

    function sendPrompt() {
        var text = promptInput.value.trim();

        if (!text) {
            if (!busy && hasPending() && !queue[queuePos].auto) {
                sendFromQueue();
            }
            return;
        }

        promptInput.value = '';
        autoResize();

        // /btw: side question — runs independently, never queued, works while busy
        if (text.toLowerCase().startsWith('/btw ')) {
            var btwQuestion = text.slice(5).trim();
            if (btwQuestion) executeBtw(btwQuestion);
            return;
        }

        // Slash commands: try skill injection first, then fallback to REST
        if (text.startsWith('/')) {
            hideSkillSuggest();
            var spaceIdx = text.indexOf(' ');
            var cmdName = spaceIdx > 0 ? text.slice(1, spaceIdx) : text.slice(1);
            var cmdArgs = spaceIdx > 0 ? text.slice(spaceIdx + 1).trim() : '';
            executeSkillCommand(cmdName, cmdArgs, text);
            return;
        }

        // Always add to queue, then send if not busy
        addToQueue(text);
        if (!busy) {
            processQueue();
        } else {
            renderQueue();
            showQueue();
        }
    }

    async function executeSlashCommand(text) {
        try {
            var resp = await fetch(apiUrl('api/command'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text })
            });
            var events = await resp.json();
            events.forEach(handleEvent);
        } catch (e) {
            appendMessage('error', 'Command failed: ' + e.message);
        }
    }

    // ── Skill command execution ───────────────────────────────────────────────

    var skillsCache = null; // cached { skills: [{name, description}] }

    function loadSkillsCache() {
        if (skillsCache) return Promise.resolve(skillsCache);
        return fetch('/api/extensions')
            .then(function(r) { return r.json(); })
            .then(function(d) { skillsCache = d; return skillsCache; });
    }

    /** Strip YAML frontmatter (--- ... ---) from markdown text. */
    function stripFrontmatter(md) {
        var lines = md.split('\n');
        if (!lines.length || lines[0].trim() !== '---') return md;
        for (var i = 1; i < lines.length; i++) {
            if (lines[i].trim() === '---') {
                return lines.slice(i + 1).join('\n').replace(/^\n+/, '');
            }
        }
        return md;
    }

    /**
     * Try to load cmdName as a skill. If found, inject content + args and queue
     * as a regular prompt. If not found (404), fall back to REST slash command.
     */
    async function executeSkillCommand(cmdName, cmdArgs, originalText) {
        try {
            var resp = await fetch('/api/extensions/content?type=skills&name='
                + encodeURIComponent(cmdName));
            if (!resp.ok) {
                // Not a skill — delegate to original slash command handler
                executeSlashCommand(originalText);
                return;
            }
            var raw = await resp.text();
            var skillBody = stripFrontmatter(raw);
            // Combine: skill instructions + separator + user arguments
            var combined = cmdArgs
                ? skillBody + '\n\n---\n\n' + cmdArgs
                : skillBody;
            addToQueue(combined);
            if (!busy) {
                processQueue();
            } else {
                renderQueue();
                showQueue();
            }
        } catch (e) {
            appendMessage('error', 'Skill load failed: ' + e.message);
        }
    }

    // ── Skill suggest popup ───────────────────────────────────────────────────

    var skillSuggest     = document.getElementById('skill-suggest');
    var skillSuggestList = [];   // currently shown skills
    var skillSuggestIdx  = -1;   // keyboard selection index

    function renderSkillSuggest(skills, typed) {
        skillSuggestList = skills;
        skillSuggestIdx  = -1;
        skillSuggest.innerHTML = skills.map(function(s, i) {
            // Bold-highlight the typed prefix
            var hi = '/' + escapeHtml(typed)
                   + '<em>' + escapeHtml(s.name.slice(typed.length)) + '</em>';
            return '<div class="skill-suggest-item" data-idx="' + i + '">'
                + '<span class="skill-suggest-name">' + hi + '</span>'
                + '<span class="skill-suggest-desc">'
                + escapeHtml(s.description || '') + '</span>'
                + '</div>';
        }).join('');
        skillSuggest.style.display = 'block';
    }

    function hideSkillSuggest() {
        skillSuggest.style.display = 'none';
        skillSuggestList = [];
        skillSuggestIdx  = -1;
    }

    function applySkillSuggest(idx) {
        var item = skillSuggestList[idx];
        if (!item) return;
        promptInput.value = '/' + item.name + ' ';
        promptInput.focus();
        hideSkillSuggest();
        autoResize();
    }

    function updateSkillSuggest() {
        var val = promptInput.value;
        // Only trigger when the whole input starts with /  and has no space yet
        if (!val.startsWith('/') || val.startsWith('/btw')) {
            hideSkillSuggest();
            return;
        }
        if (val.indexOf(' ') >= 0) {
            hideSkillSuggest(); // command already completed
            return;
        }
        var typed = val.slice(1); // text after /
        loadSkillsCache().then(function(cache) {
            var matches = (cache.skills || []).filter(function(s) {
                return s.name.toLowerCase().startsWith(typed.toLowerCase());
            });
            // Hide if no matches, or the only match is an exact hit (already complete)
            if (!matches.length
                || (matches.length === 1 && matches[0].name.toLowerCase() === typed.toLowerCase())) {
                hideSkillSuggest();
                return;
            }
            renderSkillSuggest(matches, typed);
        }).catch(hideSkillSuggest);
    }

    skillSuggest.addEventListener('click', function(e) {
        var item = e.target.closest('.skill-suggest-item');
        if (!item) return;
        applySkillSuggest(parseInt(item.dataset.idx, 10));
    });

    async function executePrompt(text) {
        // Display user message
        appendMessage('user', text);
        busy = true;
        cancelBtn.disabled = false;

        // Show immediate thinking indicator (before API responds)
        currentAssistantMsg = document.createElement('div');
        currentAssistantMsg.className = 'message assistant streaming';
        currentAssistantMsg.innerHTML = '<span class="thinking-indicator">Waiting for response...</span>';
        chatArea.appendChild(currentAssistantMsg);
        forceScrollToBottom();
        activityLabel.setAttribute('data-base', 'Waiting for response...');
        activityLabel.textContent = 'Waiting for response...';
        startThinkingTimer();

        // Submit prompt via POST; events arrive through EventSource
        try {
            var response = await fetch(apiUrl('api/chat'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text, model: modelSelect.value })
            });
            if (!response.ok) {
                stopThinkingTimer();
                if (currentAssistantMsg && !currentAssistantText) {
                    currentAssistantMsg.remove();
                    currentAssistantMsg = null;
                }
                var errText = await response.text();
                appendMessage('error', 'HTTP ' + response.status + ': ' + errText.substring(0, 200));
                busy = false;
                cancelBtn.disabled = true;
                processQueue();
                return;
            }
            var result = await response.json();
            if (result.type === 'error') {
                appendMessage('error', result.content);
                busy = false;
                cancelBtn.disabled = true;
                processQueue();
            }
            // Events will arrive through the EventSource connection
        } catch (e) {
            // Clean up thinking indicator if no content was streamed
            stopThinkingTimer();
            if (currentAssistantMsg && !currentAssistantText) {
                currentAssistantMsg.remove();
                currentAssistantMsg = null;
            }
            appendMessage('error', 'Request failed: ' + e.message);
            busy = false;
            cancelBtn.disabled = true;
            processQueue();
        }
    }

    async function cancelRequest() {
        try {
            await fetch(apiUrl('api/cancel'), { method: 'POST' });
        } catch (e) {
            // ignore
        }
        appendMessage('info', 'Cancelled');
    }

    // --- Prediction popup ---

    var predictPopup = document.getElementById('predict-popup');
    var predictSelectedIdx = -1;
    var predictCandidates = [];
    var predictFetching = false;

    function showPredictPopup(candidates) {
        predictCandidates = candidates;
        predictSelectedIdx = -1;
        predictPopup.innerHTML = '';
        candidates.forEach(function (text, idx) {
            var item = document.createElement('div');
            item.className = 'predict-item';
            var key = document.createElement('span');
            key.className = 'predict-key';
            key.textContent = (idx + 1);
            var span = document.createElement('span');
            span.className = 'predict-text';
            span.textContent = text;
            item.appendChild(key);
            item.appendChild(span);
            item.addEventListener('click', function () {
                acceptPrediction(idx);
            });
            predictPopup.appendChild(item);
        });
        predictPopup.style.display = 'block';
    }

    function hidePredictPopup() {
        predictPopup.style.display = 'none';
        predictCandidates = [];
        predictSelectedIdx = -1;
        predictFetching = false;
    }

    function acceptPrediction(idx) {
        if (idx < 0 || idx >= predictCandidates.length) return;
        var text = predictCandidates[idx];
        var curVal = promptInput.value;
        // Add a space before appending if the current text doesn't end with whitespace
        var separator = (curVal.length > 0 && !/\s$/.test(curVal)) ? '' : '';
        promptInput.value = curVal + separator + text;
        autoResize();
        hidePredictPopup();
        promptInput.focus();
    }

    function updatePredictSelection(idx) {
        var items = predictPopup.querySelectorAll('.predict-item');
        items.forEach(function (el, i) {
            el.classList.toggle('selected', i === idx);
        });
        predictSelectedIdx = idx;
    }

    function buildPredictContext() {
        // Build context from recent conversation history (last 10 turns)
        var recent = chatHistory.slice(-10);
        var parts = [];
        for (var i = 0; i < recent.length; i++) {
            var entry = recent[i];
            if (entry.role === 'user') {
                parts.push('User: ' + entry.text);
            } else if (entry.role === 'assistant') {
                // Truncate long assistant responses
                var t = entry.text.length > 500
                    ? entry.text.substring(0, 500) + '...' : entry.text;
                parts.push('Assistant: ' + t);
            }
        }
        return parts.join('\n');
    }

    async function fetchPredictions() {
        var text = promptInput.value.trim();
        if (!text) return;
        predictFetching = true;
        predictPopup.innerHTML = '<div class="predict-loading">Predicting...</div>';
        predictPopup.style.display = 'block';

        try {
            var resp = await fetch(apiUrl('api/predict'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    text: text,
                    history: buildPredictContext(),
                    n: 5
                })
            });
            var data = await resp.json();
            if (data.candidates && data.candidates.length > 0) {
                showPredictPopup(data.candidates);
            } else {
                predictPopup.innerHTML = '<div class="predict-loading">No predictions</div>';
                setTimeout(hidePredictPopup, 1500);
            }
        } catch (err) {
            predictPopup.innerHTML = '<div class="predict-loading">Prediction failed</div>';
            setTimeout(hidePredictPopup, 1500);
        }
        predictFetching = false;
    }

    // --- Input handling (IME-safe) ---

    promptInput.addEventListener('keydown', function (e) {
        // Skill suggest popup navigation
        if (skillSuggest.style.display !== 'none' && skillSuggestList.length > 0) {
            if (e.key === 'Escape') {
                e.preventDefault();
                hideSkillSuggest();
                return;
            }
            if (e.key === 'ArrowDown' || (e.key === 'Tab' && !e.shiftKey)) {
                e.preventDefault();
                skillSuggestIdx = Math.min(skillSuggestIdx + 1, skillSuggestList.length - 1);
                skillSuggest.querySelectorAll('.skill-suggest-item').forEach(function(el, i) {
                    el.classList.toggle('selected', i === skillSuggestIdx);
                });
                return;
            }
            if (e.key === 'ArrowUp') {
                e.preventDefault();
                skillSuggestIdx = Math.max(skillSuggestIdx - 1, 0);
                skillSuggest.querySelectorAll('.skill-suggest-item').forEach(function(el, i) {
                    el.classList.toggle('selected', i === skillSuggestIdx);
                });
                return;
            }
            if (e.key === 'Enter' && !e.shiftKey && skillSuggestIdx >= 0) {
                e.preventDefault();
                applySkillSuggest(skillSuggestIdx);
                return;
            }
        }

        // Prediction popup navigation
        if (predictPopup.style.display !== 'none' && predictCandidates.length > 0) {
            if (e.key === 'Escape') {
                e.preventDefault();
                hidePredictPopup();
                return;
            }
            if (e.key === 'ArrowDown' || (e.key === 'Tab' && !e.shiftKey)) {
                e.preventDefault();
                var next = (predictSelectedIdx + 1) % predictCandidates.length;
                updatePredictSelection(next);
                return;
            }
            if (e.key === 'ArrowUp') {
                e.preventDefault();
                var prev = predictSelectedIdx <= 0 ? predictCandidates.length - 1 : predictSelectedIdx - 1;
                updatePredictSelection(prev);
                return;
            }
            if (e.key === 'Enter' && !e.shiftKey && predictSelectedIdx >= 0) {
                e.preventDefault();
                acceptPrediction(predictSelectedIdx);
                return;
            }
            // Number keys 1-9 to select directly
            if (e.key >= '1' && e.key <= '9') {
                var num = parseInt(e.key) - 1;
                if (num < predictCandidates.length) {
                    e.preventDefault();
                    acceptPrediction(num);
                    return;
                }
            }
        }

        // Tab -> fetch predictions (when popup not visible)
        if (e.key === 'Tab' && !e.shiftKey && !e.isComposing && promptInput.value.trim()) {
            e.preventDefault();
            if (!predictFetching) {
                fetchPredictions();
            }
            return;
        }

        if (e.key === 'Enter' && e.shiftKey && !e.isComposing) {
            e.preventDefault();
            hidePredictPopup();
            sendPrompt();
        }
    });

    promptInput.addEventListener('input', function () {
        autoResize();
        // Hide prediction popup when user continues typing
        if (predictPopup.style.display !== 'none') {
            hidePredictPopup();
        }
        // Update skill suggest popup
        updateSkillSuggest();
    });

    promptInput.addEventListener('blur', function () {
        // Delay to allow click on popup items
        setTimeout(function () {
            if (!predictPopup.contains(document.activeElement)) {
                hidePredictPopup();
            }
            if (!skillSuggest.contains(document.activeElement)) {
                hideSkillSuggest();
            }
        }, 200);
    });

    function autoResize() {
        promptInput.style.height = 'auto';
        promptInput.style.height = Math.min(promptInput.scrollHeight, 200) + 'px';
    }

    sendBtn.addEventListener('click', sendPrompt);

    function showQueue() {
        queueArea.style.display = 'block';
        queueResizeHandle.style.display = 'block';
    }

    function hideQueue() {
        queueArea.style.display = 'none';
        queueResizeHandle.style.display = 'none';
    }

    queueBtn.addEventListener('click', function () {
        var text = promptInput.value.trim();
        if (text) {
            promptInput.value = '';
            autoResize();
            queue.push({ text: text, auto: false });
            trimQueue();
            showQueue();
            renderQueue();
            saveQueue();
            queueArea.scrollTop = queueArea.scrollHeight;
        } else {
            if (queueArea.style.display === 'none' || !queueArea.style.display) {
                showQueue();
                renderQueue();
                queueArea.scrollTop = queueArea.scrollHeight;
            } else {
                hideQueue();
            }
        }
    });

    cancelBtn.addEventListener('click', cancelRequest);

    document.getElementById('save-chat-btn').addEventListener('click', saveChatAsMarkdown);

    document.getElementById('clear-chat-btn').addEventListener('click', function () {
        chatArea.innerHTML = '';
        chatHistory = [];
        currentAssistantMsg = null;
        currentAssistantText = '';
        needsParagraphBreak = false;
        busy = false;
        cancelBtn.disabled = true;
        stopThinkingTimer();
        queue = [];
        queuePos = 0;
        pendingPrompt = false;
        localStorage.removeItem(HISTORY_KEY);
        promptInput.focus();
    });

    modelSelect.addEventListener('change', async function () {
        var selected = modelSelect.value;
        var option = modelSelect.options[modelSelect.selectedIndex];
        var isLocal = option && option.getAttribute('data-type') === 'local';

        // Persist selected model across page reloads
        try {
            localStorage.setItem(MODEL_KEY, selected);
            console.log('[chat-ui] model saved: ' + selected + ' verify=' + localStorage.getItem(MODEL_KEY));
        } catch (e) {
            console.error('[chat-ui] model save FAILED:', e);
        }

        // Local models: no server-side /model command needed (model sent per-request)
        if (isLocal) {
            appendMessage('info', 'Switched to local model: ' + selected);
            return;
        }

        // Claude models: update server-side config
        try {
            var resp = await fetch(apiUrl('api/command'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: '/model ' + selected })
            });
            var events = await resp.json();
            events.forEach(handleEvent);
        } catch (e) {
            appendMessage('error', 'Failed to change model: ' + e.message);
        }
    });

    document.getElementById('refresh-models-btn').addEventListener('click', function () {
        loadModels();
    });

    // --- Load app config (title, keybind, auth, logs) ---
    fetch('api/config')
        .then(function (resp) { return resp.json(); })
        .then(function (cfg) {
            if (cfg.title) {
                document.title = cfg.title;
                var h1 = document.querySelector('header h1');
                if (h1) h1.textContent = cfg.title;
                var loginTitle = document.getElementById('login-title');
                if (loginTitle) loginTitle.textContent = cfg.title;
            }
            if (cfg.keybind) {
                activeKeybind = cfg.keybind;
            }
            if (cfg.authenticated === false) {
                showAuthDialog();
            }
            if (cfg.logsEnabled === false) {
                if (logPanel) logPanel.style.display = 'none';
            }
        })
        .catch(function () {});

    // --- Initial data load ---
    loadModels();
    restoreHistory();
    restoreQueue();
    connectSSE();
    promptInput.focus();

    function showAuthDialog() {
        var overlay = document.getElementById('auth-overlay');
        var input = document.getElementById('api-key-input');
        var submitBtn = document.getElementById('auth-submit-btn');
        var errorEl = document.getElementById('auth-error');

        if (!overlay) return;
        overlay.style.display = 'flex';

        function submitApiKey() {
            var key = input.value.trim();
            if (!key) {
                errorEl.textContent = 'Please enter your API key.';
                errorEl.style.display = 'block';
                return;
            }
            submitBtn.disabled = true;
            submitBtn.textContent = 'Verifying...';
            errorEl.style.display = 'none';

            fetch('api/auth', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({apiKey: key})
            })
            .then(function(resp) { return resp.json(); })
            .then(function(result) {
                if (result.type === 'error') {
                    errorEl.textContent = result.content || 'Failed to set API key.';
                    errorEl.style.display = 'block';
                    submitBtn.disabled = false;
                    submitBtn.textContent = 'Submit';
                } else {
                    overlay.style.display = 'none';
                }
            })
            .catch(function() {
                errorEl.textContent = 'Network error. Please try again.';
                errorEl.style.display = 'block';
                submitBtn.disabled = false;
                submitBtn.textContent = 'Submit';
            });
        }

        submitBtn.addEventListener('click', submitApiKey);
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') submitApiKey();
        });
        input.focus();
    }

    // --- Server Log Panel ---

    var MAX_LOG_LINES = 500;
    var logLoaded = false;

    function formatLogTime(ts) {
        var d = new Date(ts);
        var hh = String(d.getHours()).padStart(2, '0');
        var mm = String(d.getMinutes()).padStart(2, '0');
        var ss = String(d.getSeconds()).padStart(2, '0');
        var ms = String(d.getMilliseconds()).padStart(3, '0');
        return hh + ':' + mm + ':' + ss + '.' + ms;
    }

    function shortLogger(name) {
        if (!name) return '';
        var parts = name.split('.');
        return parts[parts.length - 1];
    }

    function appendLog(event) {
        if (!logPanel.open) return;
        var line = document.createElement('div');
        line.className = 'log-line log-' + (event.logLevel || 'INFO').toLowerCase();
        var time = event.timestamp ? formatLogTime(event.timestamp) : '';
        line.textContent = time + ' [' + (event.logLevel || '?') + '] '
            + shortLogger(event.loggerName) + ': ' + (event.content || '');
        logContent.appendChild(line);
        trimLogLines();
        logContent.scrollTop = logContent.scrollHeight;
    }

    function appendLogBatch(events) {
        for (var i = 0; i < events.length; i++) {
            var event = events[i];
            var line = document.createElement('div');
            line.className = 'log-line log-' + (event.logLevel || 'INFO').toLowerCase();
            var time = event.timestamp ? formatLogTime(event.timestamp) : '';
            line.textContent = time + ' [' + (event.logLevel || '?') + '] '
                + shortLogger(event.loggerName) + ': ' + (event.content || '');
            logContent.appendChild(line);
        }
        trimLogLines();
        logContent.scrollTop = logContent.scrollHeight;
    }

    function trimLogLines() {
        while (logContent.children.length > MAX_LOG_LINES) {
            logContent.removeChild(logContent.firstChild);
        }
    }

    logPanel.addEventListener('toggle', function () {
        if (logPanel.open && !logLoaded) {
            logLoaded = true;
            fetch('api/logs')
                .then(function (resp) { return resp.json(); })
                .then(function (logs) { appendLogBatch(logs); })
                .catch(function () { /* ignore */ });
        }
    });

    // --- BTW overlay close ---

    document.getElementById('btw-close').addEventListener('click', function () {
        document.getElementById('btw-overlay').style.display = 'none';
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape'
                && document.getElementById('btw-overlay').style.display !== 'none') {
            document.getElementById('btw-overlay').style.display = 'none';
        }
    });

    // ── Extensions panel ──────────────────────────────────────────────────────
    (function initExtensions() {
        var extBtn     = document.getElementById('extensions-btn');
        var extPanel   = document.getElementById('extensions-panel');
        var extTabs    = document.querySelectorAll('.ext-tab');
        var extContent = document.getElementById('ext-content');
        var extData    = null;
        var extActive  = 'skills';

        function renderTab(tab) {
            extActive = tab;
            if (!extData) { extContent.innerHTML = '<div class="ext-loading">Loading…</div>'; return; }
            var map = {
                skills: [['Command','Description'], extData.skills,
                          function(r) { return ['/'+r.name, r.description]; }, 'skills'],
                agents: [['Agent','Description'], extData.agents,
                          function(r) { return [r.name, r.description]; }, 'agents'],
                hooks:  [['Event','Matcher','Command'], extData.hooks,
                          function(r) { return [r.event, r.matcher||'—', r.command]; }, null],
                mcp:    [['Name','Type','Description / Endpoint'], extData.mcpServers,
                          function(r) { return [r.name, r.type, r.description||r.endpoint||'']; }, null]
            };
            var cfg = map[tab];
            if (!cfg) return;
            extContent.innerHTML = buildTable(cfg[0], cfg[1], cfg[2], cfg[3]);
        }

        function buildTable(headers, rows, rowFn, clickType) {
            if (!rows || !rows.length)
                return '<div class="ext-loading">No entries found.</div>';
            var h = '<table class="ext-table"><thead><tr>';
            headers.forEach(function(hd) { h += '<th>'+esc(hd)+'</th>'; });
            h += '</tr></thead><tbody>';
            rows.forEach(function(r) {
                var cells = rowFn(r);
                var rowCls = clickType ? ' class="ext-clickable"' : '';
                var rowData = clickType
                    ? ' data-type="'+esc(clickType)+'" data-name="'+esc(r.name||'')+'"'
                    : '';
                h += '<tr'+rowCls+rowData+'>';
                cells.forEach(function(c, i) {
                    var cls = i===0 ? 'ext-name' : (i===headers.length-1 ? 'ext-desc' : '');
                    h += '<td'+(cls?' class="'+cls+'"':'')+'>'+esc(c||'')+'</td>';
                });
                h += '</tr>';
            });
            return h + '</tbody></table>';
        }

        function esc(s) {
            return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;')
                            .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
        }

        function setTabCounts() {
            if (!extData) return;
            var c = {skills:extData.skills.length, agents:extData.agents.length,
                     hooks:extData.hooks.length, mcp:extData.mcpServers.length};
            var labels = {skills:'Skills',agents:'Agents',hooks:'Hooks',mcp:'MCP Servers'};
            extTabs.forEach(function(t) {
                var k = t.dataset.tab;
                t.innerHTML = labels[k]+' <span class="ext-count">'+'('+( c[k]||0 )+')'+'</span>';
            });
        }

        function loadData() {
            if (extData) return;
            fetch('/api/extensions')
                .then(function(r) { return r.json(); })
                .then(function(d) { extData = d; setTabCounts(); renderTab(extActive); })
                .catch(function(e) {
                    extContent.innerHTML = '<div class="ext-loading">Error: '+esc(e.message)+'</div>';
                });
        }

        extBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            var open = extPanel.style.display !== 'none';
            extPanel.style.display = open ? 'none' : 'flex';
            if (!open) loadData();
        });

        extTabs.forEach(function(t) {
            t.addEventListener('click', function() {
                extTabs.forEach(function(x) { x.classList.remove('active'); });
                t.classList.add('active');
                renderTab(t.dataset.tab);
            });
        });

        // Click on skill/agent row → open content dialog
        extContent.addEventListener('click', function(e) {
            var row = e.target.closest('tr[data-type]');
            if (!row) return;
            openExtDialog(row.dataset.type, row.dataset.name);
        });

        document.addEventListener('click', function(e) {
            if (!document.getElementById('extensions-menu').contains(e.target))
                extPanel.style.display = 'none';
        });

        // ── Extension content dialog ─────────────────────────────────────────
        var extDialogOverlay = document.getElementById('ext-dialog-overlay');
        var extDialogTitle   = document.getElementById('ext-dialog-title');
        var extDialogBody    = document.getElementById('ext-dialog-body');

        function openExtDialog(type, name) {
            var prefix = type === 'skills' ? '/' : '';
            extDialogTitle.textContent = prefix + name;
            extDialogBody.innerHTML = '<div class="ext-loading">Loading…</div>';
            extDialogOverlay.style.display = 'flex';
            fetch('/api/extensions/content?type=' + encodeURIComponent(type)
                  + '&name=' + encodeURIComponent(name))
                .then(function(r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.text();
                })
                .then(function(md) {
                    extDialogBody.innerHTML = (typeof marked !== 'undefined')
                        ? marked.parse(md)
                        : '<pre>' + esc(md) + '</pre>';
                })
                .catch(function(err) {
                    extDialogBody.innerHTML = '<div class="ext-loading">Error: ' + esc(err.message) + '</div>';
                });
        }

        document.getElementById('ext-dialog-close').addEventListener('click', function() {
            extDialogOverlay.style.display = 'none';
        });
        extDialogOverlay.addEventListener('click', function(e) {
            if (e.target === extDialogOverlay) extDialogOverlay.style.display = 'none';
        });
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && extDialogOverlay.style.display !== 'none')
                extDialogOverlay.style.display = 'none';
        });
    })();
    // ── End Extensions panel ──────────────────────────────────────────────────

    } // end doInitApp
})();
