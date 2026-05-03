const chatMessages = document.getElementById('chat-messages');
const chatForm = document.getElementById('chat-form');
const chatInput = document.getElementById('chat-input');
const chatStatus = document.getElementById('chat-status');
const chatSources = document.getElementById('chat-sources');
const promptChips = document.querySelectorAll('#prompt-chips .chip');

const searchForm = document.getElementById('search-form');
const searchInput = document.getElementById('search-input');
const searchStatus = document.getElementById('search-status');
const searchResults = document.getElementById('search-results');

const uploadForm = document.getElementById('upload-form');
const uploadInput = document.getElementById('upload-input');
const uploadBtn = document.getElementById('upload-btn');
const uploadModal = document.getElementById('upload-modal');
const modalClose = document.getElementById('modal-close');
const modalCancel = document.getElementById('modal-cancel');
const uploadZoneModal = document.getElementById('upload-zone-modal');
const fileNameDisplay = document.getElementById('file-name-display');
const fileNameText = document.getElementById('file-name-text');
const uploadStatus = document.getElementById('upload-status');
const uploadVisibility = document.getElementById('upload-visibility');

const docTableBody = document.getElementById('doc-table-body');
const docSearchInput = document.getElementById('doc-search-input');
const docVisibilityFilter = document.getElementById('doc-visibility-filter');
const docTypeFilter = document.getElementById('doc-type-filter');
const docStatus = document.getElementById('doc-status');

const footerYear = document.getElementById('footer-year');

const hasChat = !!(chatForm && chatInput && chatMessages);
const hasSearch = !!(searchForm && searchInput && searchStatus && searchResults);
const hasUpload = !!(uploadForm && uploadInput && uploadModal);
const hasDocTable = !!docTableBody;

const API_BASE = getApiBase();

function getApiBase() {
    const meta = document.querySelector('meta[name="api-base"]');
    const metaValue = meta?.getAttribute('content')?.trim();
    if (metaValue) {
        return metaValue.replace(/\/+$/, '');
    }
    if (window.location.protocol === 'file:') {
        return 'http://localhost:8000';
    }
    if (window.location.origin && window.location.origin !== 'null') {
        return window.location.origin;
    }
    return 'http://localhost:8000';
}

const state = {
    userId: 'test',
    activeStream: null,
    selectedFile: null,
    allDocuments: [],
    shouldRefreshDocuments: false
};

const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
        if (entry.isIntersecting) {
            entry.target.classList.add('visible');
        }
    });
}, { threshold: 0.15 });

document.querySelectorAll('[data-animate]').forEach((element, index) => {
    element.style.transitionDelay = `${index * 0.1}s`;
    observer.observe(element);
});

if (hasChat && promptChips.length) {
    promptChips.forEach((chip) => {
        chip.addEventListener('click', () => {
            chatInput.value = chip.textContent.trim();
            chatInput.focus();
        });
    });
}

if (hasChat) {
    chatForm.addEventListener('submit', (event) => {
        event.preventDefault();
        const message = chatInput.value.trim();
        if (!message) {
            return;
        }
        chatInput.value = '';
        streamChat(message);
    });
}

if (hasSearch) {
    searchForm.addEventListener('submit', (event) => {
        event.preventDefault();
        const query = searchInput.value.trim();
        if (!query) {
            return;
        }
        runSearch(query);
    });
}

// 模态框控制
if (uploadBtn && uploadModal) {
    uploadBtn.addEventListener('click', () => {
        uploadModal.style.display = 'flex';
        state.selectedFile = null;
        if (fileNameDisplay) {
            fileNameDisplay.style.display = 'none';
        }
        if (uploadStatus) {
            setStatus(uploadStatus, '');
        }
        if (uploadInput) {
            uploadInput.value = '';
        }
    });
}

if (modalClose && uploadModal) {
    modalClose.addEventListener('click', () => {
        closeUploadModal();
    });
}

if (modalCancel && uploadModal) {
    modalCancel.addEventListener('click', () => {
        closeUploadModal();
    });
}

if (uploadModal) {
    uploadModal.querySelector('.modal-overlay')?.addEventListener('click', () => {
        closeUploadModal();
    });
}

// 文件上传区域
if (uploadInput) {
    uploadInput.addEventListener('change', () => {
        const file = uploadInput.files[0];
        setSelectedFile(file);
    });
}

if (uploadZoneModal) {
    uploadZoneModal.addEventListener('click', () => {
        uploadInput?.click();
    });

    uploadZoneModal.addEventListener('dragover', (event) => {
        event.preventDefault();
        uploadZoneModal.classList.add('is-dragover');
    });

    uploadZoneModal.addEventListener('dragleave', () => {
        uploadZoneModal.classList.remove('is-dragover');
    });

    uploadZoneModal.addEventListener('drop', (event) => {
        event.preventDefault();
        uploadZoneModal.classList.remove('is-dragover');
        const file = event.dataTransfer.files[0];
        if (file) {
            const dataTransfer = new DataTransfer();
            dataTransfer.items.add(file);
            uploadInput.files = dataTransfer.files;
            setSelectedFile(file);
        }
    });
}

if (hasUpload) {
    uploadForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        if (!state.selectedFile) {
            setStatus(uploadStatus, '请先选择文件');
            return;
        }

        setStatus(uploadStatus, '正在上传并解析文档...');

        try {
            const formData = new FormData();
            formData.append('file', state.selectedFile);
            formData.append('userId', state.userId);
            if (uploadVisibility?.value) {
                formData.append('visibility', uploadVisibility.value);
            }

            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 60000);

            const response = await fetch(`${API_BASE}/api/upload`, {
                method: 'POST',
                body: formData,
                signal: controller.signal
            });
            clearTimeout(timeoutId);

            const rawText = await response.text();
            let result = null;
            if (rawText) {
                try {
                    result = JSON.parse(rawText);
                } catch (error) {
                    result = null;
                }
            }

            if (response.ok && result?.code === 200) {
                const fileName = result.data?.fileName || state.selectedFile.name;
                const message = result.data?.message || '上传成功';
                setStatus(uploadStatus, `${message}：${fileName}`);
                state.shouldRefreshDocuments = true;

                // 重置表单
                uploadInput.value = '';
                state.selectedFile = null;
                if (fileNameDisplay) {
                    fileNameDisplay.style.display = 'none';
                }
                if (uploadVisibility) {
                    uploadVisibility.value = 'PRIVATE';
                }

                // 延迟关闭模态框并刷新列表
                setTimeout(() => {
                    closeUploadModal();
                }, 1500);
            } else {
                const errorMessage = result?.message || `上传失败（状态码 ${response.status}）`;
                setStatus(uploadStatus, errorMessage);
            }
        } catch (error) {
            const message = error?.name === 'AbortError'
                ? '上传超时，请稍后在文档列表查看处理状态'
                : '上传出现异常，请检查服务状态';
            setStatus(uploadStatus, message);
        }
    });
}

// 文档搜索和过滤
if (docSearchInput) {
    docSearchInput.addEventListener('input', filterDocuments);
}

if (docVisibilityFilter) {
    docVisibilityFilter.addEventListener('change', filterDocuments);
}

if (docTypeFilter) {
    docTypeFilter.addEventListener('change', filterDocuments);
}

if (footerYear) {
    footerYear.textContent = new Date().getFullYear();
}

// 页面加载时获取文档列表
if (hasDocTable) {
    loadDocumentList();
}

function setSelectedFile(file) {
    state.selectedFile = file;
    if (fileNameDisplay && fileNameText) {
        if (file) {
            fileNameText.textContent = `${file.name} (${formatFileSize(file.size)})`;
            fileNameDisplay.style.display = 'block';
        } else {
            fileNameDisplay.style.display = 'none';
        }
    }
}

function createMessage(role, text, streaming = false) {
    if (!chatMessages) {
        return { message: null, bubbleText: null, feedbackButtons: null };
    }

    // 隐藏欢迎屏幕
    const welcomeScreen = chatMessages.querySelector('.welcome-screen');
    if (welcomeScreen) {
        welcomeScreen.remove();
    }

    const message = document.createElement('div');
    message.className = `message ${role}${streaming ? ' streaming' : ''}`;

    const avatar = document.createElement('div');
    avatar.className = 'avatar';
    avatar.textContent = role === 'assistant' ? 'AI' : '你';

    const bubble = document.createElement('div');
    bubble.className = 'bubble';

    const bubbleText = document.createElement('div');
    bubbleText.className = 'bubble-text';
    bubbleText.textContent = text;

    bubble.appendChild(bubbleText);
    let feedbackButtons = null;
    if (role === 'assistant') {
        feedbackButtons = buildFeedbackControls(message);
        bubble.appendChild(feedbackButtons.container);
    }
    message.appendChild(avatar);
    message.appendChild(bubble);

    chatMessages.appendChild(message);
    scrollChatToBottom();

    return { message, bubbleText, feedbackButtons };
}

function scrollChatToBottom() {
    if (!chatMessages) {
        return;
    }
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

function updateChatStatus(text) {
    if (chatStatus) {
        chatStatus.textContent = text;
    }
    updateSourcesPanel();
}

function setStatus(element, text) {
    if (element) {
        element.textContent = text;
    }
}

function closeUploadModal() {
    if (uploadModal) {
        uploadModal.style.display = 'none';
    }
    if (uploadStatus) {
        setStatus(uploadStatus, '');
    }
    if (state.shouldRefreshDocuments && hasDocTable) {
        loadDocumentList();
    }
    state.shouldRefreshDocuments = false;
}

function streamChat(message) {
    createMessage('user', message);

    if (state.activeStream) {
        state.activeStream.close();
        state.activeStream = null;
    }

    const assistant = createMessage('assistant', '', true);
    updateChatStatus('生成中...');
    resetSources();

    const url = `${API_BASE}/api/chat/stream?message=${encodeURIComponent(message)}&userId=${encodeURIComponent(state.userId)}`;
    const source = new EventSource(url);
    state.activeStream = source;

    let hasContent = false;
    let hasNotice = false;

    source.onmessage = (event) => {
        hasContent = true;
        assistant.bubbleText.textContent += event.data;
        scrollChatToBottom();
    };

    source.addEventListener('notice', (event) => {
        hasNotice = true;
        if (event?.data) {
            updateChatStatus(event.data);
        }
    });

    source.addEventListener('sources', (event) => {
        try {
            const data = JSON.parse(event.data);
            renderSources(data);
        } catch (error) {
            resetSources('来源失败');
        }
    });

    source.addEventListener('messageId', (event) => {
        if (event?.data) {
            setMessageId(assistant, event.data);
        }
    });

    source.addEventListener('done', () => {
        assistant.message.classList.remove('streaming');
        updateChatStatus('回答完成');
        source.close();
        state.activeStream = null;
    });

    source.onerror = () => {
        source.close();
        state.activeStream = null;
        if (!hasContent && !hasNotice) {
            fallbackChat(message, assistant);
        } else {
            assistant.message.classList.remove('streaming');
        updateChatStatus('连接中断');
        }
    };
}

async function fallbackChat(message, assistant) {
    updateChatStatus('切换模式...');
    try {
        const response = await fetch(`${API_BASE}/api/chat`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ message, userId: state.userId })
        });

        const result = await response.json();
        if (result.code === 200) {
            assistant.bubbleText.textContent = result.data?.response || '无回答';
            if (result.data?.sources) {
                renderSources(result.data.sources);
            } else {
                resetSources('无来源');
            }
            if (result.data?.messageId) {
                setMessageId(assistant, result.data.messageId);
            }
            updateChatStatus('回答完成');
        } else {
            assistant.bubbleText.textContent = result.message || '服务异常';
            updateChatStatus('服务异常');
        }
    } catch (error) {
        assistant.bubbleText.textContent = '服务不可用';
        updateChatStatus('服务不可用');
    } finally {
        assistant.message.classList.remove('streaming');
    }
}

function resetSources(message) {
    if (!chatSources) {
        return;
    }
    const text = message || '暂无来源';
    chatSources.innerHTML = `<li class="source-empty">${text}</li>`;
    updateSourcesPanel();
}

function renderSources(sources) {
    if (!chatSources) {
        return;
    }
    if (!Array.isArray(sources) || sources.length === 0) {
        resetSources('暂无来源');
        return;
    }

    chatSources.innerHTML = '';
    sources.slice(0, 5).forEach((source, index) => {
        const item = document.createElement('li');
        item.className = 'source-item';

        const content = document.createElement('div');
        content.className = 'source-content';

        const title = document.createElement('div');
        title.className = 'source-title';
        title.textContent = source.sourceFileName || '未知来源';

        const snippet = document.createElement('div');
        snippet.className = 'source-snippet';
        const text = source.textContent || '';
        snippet.textContent = text.length > 100 ? `${text.slice(0, 100)}...` : text;

        const meta = document.createElement('div');
        meta.className = 'source-meta';
        const score = source.relevanceScore ? source.relevanceScore.toFixed(3) : 'N/A';
        meta.textContent = `片段 #${source.chunkId ?? '-'} · 相关度 ${score}`;

        content.appendChild(title);
        content.appendChild(snippet);
        content.appendChild(meta);

        const rank = document.createElement('div');
        rank.className = 'source-rank';
        rank.textContent = String(index + 1);

        item.appendChild(content);
        item.appendChild(rank);
        chatSources.appendChild(item);
    });

    updateSourcesPanel();
}

function updateSourcesPanel() {
    const sourcesPanel = document.getElementById('sources-panel');
    if (!sourcesPanel || !chatSources) {
        return;
    }

    // 显示或隐藏来源面板
    const hasValidSources = chatSources.querySelector('.source-item');
    sourcesPanel.style.display = hasValidSources ? 'block' : 'none';
}

function buildFeedbackControls(messageElement) {
    const container = document.createElement('div');
    container.className = 'feedback';

    const up = document.createElement('button');
    up.type = 'button';
    up.className = 'feedback-btn';
    up.textContent = '👍';
    up.title = '有帮助';
    up.disabled = true;

    const down = document.createElement('button');
    down.type = 'button';
    down.className = 'feedback-btn';
    down.textContent = '👎';
    down.title = '不够准确';
    down.disabled = true;

    up.addEventListener('click', () => submitFeedback(messageElement, 5, up, down));
    down.addEventListener('click', () => submitFeedback(messageElement, 1, down, up));

    container.appendChild(up);
    container.appendChild(down);

    return { container, up, down };
}

function setMessageId(assistant, messageId) {
    if (!assistant?.message) {
        return;
    }
    assistant.message.dataset.messageId = String(messageId);
    if (assistant.feedbackButtons) {
        assistant.feedbackButtons.up.disabled = false;
        assistant.feedbackButtons.down.disabled = false;
    }
}

async function submitFeedback(messageElement, score, activeButton, otherButton) {
    const messageId = messageElement?.dataset?.messageId;
    if (!messageId) {
        return;
    }
    try {
        const response = await fetch(`${API_BASE}/api/feedback`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                messageId: Number(messageId),
                userId: state.userId,
                score
            })
        });
        const result = await response.json();
        if (result.code === 200) {
            activeButton.classList.add('active');
            otherButton.classList.remove('active');
        }
    } catch (error) {
        // ignore feedback errors in UI
    }
}

async function runSearch(query) {
    if (!hasSearch) {
        return;
    }
    searchStatus.textContent = '检索中...';
    searchResults.innerHTML = '';

    try {
        const response = await fetch(`${API_BASE}/api/search?query=${encodeURIComponent(query)}&topK=6&userId=${encodeURIComponent(state.userId)}`);
        const result = await response.json();

        if (result.code !== 200) {
            searchStatus.textContent = result.message || '检索失败';
            renderEmptyResult();
            return;
        }

        const matches = result.data || [];
        if (matches.length === 0) {
            searchStatus.textContent = '未检索到相关结果';
            renderEmptyResult();
            return;
        }

        searchStatus.textContent = `检索到 ${matches.length} 条结果`;
        matches.forEach((match) => {
            const item = document.createElement('li');
            item.className = 'result-item';

            const title = document.createElement('strong');
            title.textContent = match.sourceFileName || '未知来源';

            const snippet = document.createElement('span');
            const rawText = match.textContent || '';
            snippet.textContent = rawText.length > 120 ? `${rawText.slice(0, 120)}...` : rawText;

            const meta = document.createElement('span');
            const score = match.relevanceScore ? match.relevanceScore.toFixed(3) : 'N/A';
            meta.textContent = `片段 ${match.chunkId ?? '-'} · 相关度 ${score}`;

            item.appendChild(title);
            item.appendChild(snippet);
            item.appendChild(meta);
            searchResults.appendChild(item);
        });
    } catch (error) {
        searchStatus.textContent = '检索异常，请检查服务';
        renderEmptyResult();
    }
}

function renderEmptyResult() {
    if (!searchResults) {
        return;
    }
    searchResults.innerHTML = '<li class="result-empty">暂无结果，换个关键词试试。</li>';
}

function formatFileSize(size) {
    if (!size) {
        return '0 KB';
    }
    const units = ['B', 'KB', 'MB', 'GB'];
    let index = 0;
    let value = size;
    while (value >= 1024 && index < units.length - 1) {
        value /= 1024;
        index += 1;
    }
    return `${value.toFixed(1)} ${units[index]}`;
}

async function loadDocumentList() {
    if (!docTableBody) {
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/api/documents?userId=${encodeURIComponent(state.userId)}`);
        const result = await response.json();

        if (result.code !== 200 || !Array.isArray(result.data)) {
            state.allDocuments = [];
            renderDocumentTable([]);
            return;
        }

        state.allDocuments = result.data;
        filterDocuments();
    } catch (error) {
        state.allDocuments = [];
        renderDocumentTable([]);
    }
}

function filterDocuments() {
    if (!docTableBody) {
        return;
    }

    let filtered = [...state.allDocuments];

    // 搜索过滤
    if (docSearchInput && docSearchInput.value.trim()) {
        const search = docSearchInput.value.trim().toLowerCase();
        filtered = filtered.filter(doc =>
            (doc.originalFileName || '').toLowerCase().includes(search)
        );
    }

    // 可见性过滤
    if (docVisibilityFilter && docVisibilityFilter.value) {
        filtered = filtered.filter(doc => doc.visibility === docVisibilityFilter.value);
    }

    // 类型过滤
    if (docTypeFilter && docTypeFilter.value) {
        const ext = docTypeFilter.value.toLowerCase();
        filtered = filtered.filter(doc => {
            const fileExt = getFileExtension(doc.originalFileName || '').toLowerCase();
            return fileExt === ext ||
                   (ext === 'doc' && (fileExt === 'doc' || fileExt === 'docx')) ||
                   (ext === 'xls' && (fileExt === 'xls' || fileExt === 'xlsx')) ||
                   (ext === 'ppt' && (fileExt === 'ppt' || fileExt === 'pptx'));
        });
    }

    renderDocumentTable(filtered);
}

function renderDocumentTable(documents) {
    if (!docTableBody) {
        return;
    }

    if (documents.length === 0) {
        docTableBody.innerHTML = `
            <tr class="doc-empty-row">
                <td colspan="5">
                    <div class="doc-table-empty">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                            <path d="M14 2v6h6"/>
                        </svg>
                        <p>暂无文档</p>
                        <p class="empty-hint">点击"上传文档"按钮上传你的第一个文档</p>
                    </div>
                </td>
            </tr>
        `;
        return;
    }

    docTableBody.innerHTML = '';
    documents.forEach((doc) => {
        const tr = document.createElement('tr');

        // 文档名称
        const tdName = document.createElement('td');
        const ext = getFileExtension(doc.originalFileName || '').toLowerCase();
        const extLabel = ext.toUpperCase() || 'DOC';
        const iconClass = `doc-file-icon type-${ext}`;
        tdName.innerHTML = `
            <div class="doc-file-name">
                <div class="${iconClass}">${extLabel}</div>
                <div class="doc-file-text">
                    <strong title="${escapeHtml(doc.originalFileName || '未知文件')}">${escapeHtml(doc.originalFileName || '未知文件')}</strong>
                </div>
            </div>
        `;

        // 文件大小
        const tdSize = document.createElement('td');
        tdSize.textContent = formatFileSize(doc.fileSizeBytes);
        tdSize.style.color = 'var(--text-tertiary)';
        tdSize.style.fontFamily = 'var(--font-mono)';
        tdSize.style.fontSize = '12.5px';

        // 上传时间
        const tdDate = document.createElement('td');
        tdDate.textContent = formatDate(doc.uploadedAt);
        tdDate.style.color = 'var(--text-tertiary)';
        tdDate.style.fontSize = '12.5px';

        // 可见性
        const tdVisibility = document.createElement('td');
        const visibility = (doc.visibility || 'PRIVATE').toLowerCase();
        const isPublic = visibility === 'public';
        const visIcon = isPublic
            ? `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:11px;height:11px"><circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>`
            : `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:11px;height:11px"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`;
        const visLabel = isPublic ? '公开' : '私有';
        tdVisibility.innerHTML = `<span class="doc-visibility-badge ${visibility}">${visIcon}${visLabel}</span>`;

        // 操作
        const tdActions = document.createElement('td');
        tdActions.innerHTML = `
            <div class="doc-table-actions">
                <button class="doc-action-icon delete" title="删除文档" onclick="deleteDocument('${escapeHtml(doc.md5Hash)}')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
                    </svg>
                </button>
            </div>
        `;

        tr.appendChild(tdName);
        tr.appendChild(tdSize);
        tr.appendChild(tdDate);
        tr.appendChild(tdVisibility);
        tr.appendChild(tdActions);
        docTableBody.appendChild(tr);
    });
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function getFileExtension(fileName) {
    if (!fileName) return '';
    const parts = fileName.split('.');
    return parts.length > 1 ? parts[parts.length - 1] : '';
}

function formatDate(dateString) {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleDateString('zh-CN');
}

function getVisibilityText(visibility) {
    const map = {
        'PRIVATE': '私有',
        'PUBLIC': '公开'
    };
    return map[visibility] || '私有';
}

async function deleteDocument(md5Hash) {
    if (!confirm('确认删除该文档？此操作不可恢复')) {
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/api/documents/${md5Hash}?userId=${encodeURIComponent(state.userId)}`, {
            method: 'DELETE'
        });

        const result = await response.json();
        if (result.code === 200) {
            setStatus(docStatus, '文档已删除');
            loadDocumentList();
            setTimeout(() => {
                if (docStatus) {
                    docStatus.textContent = '';
                }
            }, 2000);
        } else {
            setStatus(docStatus, result.message || '删除失败');
        }
    } catch (error) {
        setStatus(docStatus, '删除出现异常');
    }
}
