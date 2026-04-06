import { useEffect, useState, useCallback, useRef } from "react";
import { apiDelete, apiGet, apiPostForm } from "../lib/api";
import type { DocumentItem, KnowledgeItem } from "../types";
import { useActionRequest } from "../hooks/useActionRequest";
import { CustomSelect } from "./CustomSelect";
import type { SelectOption } from "./CustomSelect";

/** 轮询间隔（毫秒） */
const POLL_INTERVAL = 3000;
/** 轮询最大时长（毫秒），超过后自动停止 */
const POLL_TIMEOUT = 5 * 60 * 1000;

export function DocumentPanel() {
  const [items, setItems] = useState<DocumentItem[]>([]);
  const [knowledgeItems, setKnowledgeItems] = useState<KnowledgeItem[]>([]);

  // Upload form state
  const [knowledgeId, setKnowledgeId] = useState("");
  const [sourceType, setSourceType] = useState<"file" | "url">("file");
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState("");
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [scheduleCron, setScheduleCron] = useState("");
  const [showUpload, setShowUpload] = useState(false);
  const [msg, setMsg] = useState("");

  // Filter state
  const [filterKnowledgeId, setFilterKnowledgeId] = useState("");
  const [searchName, setSearchName] = useState("");
  const [searchInput, setSearchInput] = useState("");

  // Polling state
  const [polling, setPolling] = useState(false);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollStartRef = useRef<number>(0);

  const listReq = useActionRequest();
  const knowledgeReq = useActionRequest();
  const actionReq = useActionRequest();

  const load = useCallback(async (kId?: string, name?: string) => {
    const params = new URLSearchParams();
    const effectiveKId = kId ?? filterKnowledgeId;
    const effectiveName = name ?? searchName;
    if (effectiveKId) params.set("knowledgeId", effectiveKId);
    if (effectiveName) params.set("name", effectiveName);
    const qs = params.toString();
    const path = `/api/rag/document/list${qs ? `?${qs}` : ""}`;
    const result = await listReq.runAction(() => apiGet<DocumentItem[]>(path), {
      errorFallback: "文档加载失败",
      onError: setMsg
    });
    if (result.ok) {
      const data = result.data || [];
      setItems(data);
      return data;
    }
    return [];
  }, [filterKnowledgeId, searchName]);

  const loadKnowledgeList = async () => {
    const result = await knowledgeReq.runAction(() => apiGet<KnowledgeItem[]>("/api/rag/knowledge/list"), {
      errorFallback: "知识库加载失败",
      onError: setMsg
    });
    if (result.ok) {
      const list = result.data || [];
      setKnowledgeItems(list);
      if (!knowledgeId && list.length > 0) {
        setKnowledgeId(String(list[0].id));
      }
    }
  };

  /** 停止轮询 */
  const stopPolling = useCallback(() => {
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    setPolling(false);
  }, []);

  /** 启动轮询，定期刷新文档列表直到所有文档处理完毕 */
  const startPolling = useCallback(() => {
    stopPolling();
    setPolling(true);
    pollStartRef.current = Date.now();

    pollTimerRef.current = setInterval(async () => {
      // 超时保护
      if (Date.now() - pollStartRef.current > POLL_TIMEOUT) {
        stopPolling();
        return;
      }

      const params = new URLSearchParams();
      if (filterKnowledgeId) params.set("knowledgeId", filterKnowledgeId);
      if (searchName) params.set("name", searchName);
      const qs = params.toString();
      const path = `/api/rag/document/list${qs ? `?${qs}` : ""}`;

      try {
        const data = await apiGet<DocumentItem[]>(path);
        setItems(data || []);
        // 如果所有文档都已处理完成（状态=2 或 -1），停止轮询
        const hasProcessing = (data || []).some(
          (doc) => doc.processingStatus === 0 || doc.processingStatus === 1
        );
        if (!hasProcessing) {
          stopPolling();
        }
      } catch {
        // 轮询失败时静默忽略，等下次重试
      }
    }, POLL_INTERVAL);
  }, [filterKnowledgeId, searchName, stopPolling]);

  // 组件卸载时清理轮询
  useEffect(() => {
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    void (async () => {
      const docs = await load();
      // 如果初始加载时存在处理中的文档，自动启动轮询
      const hasProcessing = docs.some(
        (doc) => doc.processingStatus === 0 || doc.processingStatus === 1
      );
      if (hasProcessing) {
        startPolling();
      }
    })();
    void loadKnowledgeList();
  }, []);

  // Trigger load when filter changes
  const handleFilterChange = (kId: string) => {
    setFilterKnowledgeId(kId);
    void load(kId, searchName);
  };

  const handleSearch = () => {
    setSearchName(searchInput);
    void load(filterKnowledgeId, searchInput);
  };

  const handleSearchKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      handleSearch();
    }
  };

  const handleClearSearch = () => {
    setSearchInput("");
    setSearchName("");
    void load(filterKnowledgeId, "");
  };

  const isFileSource = sourceType === "file";
  const isUrlSource = sourceType === "url";

  const upload = async () => {
    if (!knowledgeId.trim()) {
      setMsg("请输入知识库 ID");
      return;
    }
    if (isFileSource && !file) {
      setMsg("请选择文件");
      return;
    }
    if (isUrlSource && !url.trim()) {
      setMsg("请输入 URL");
      return;
    }
    if (isUrlSource && scheduleEnabled && !scheduleCron.trim()) {
      setMsg("请输入定时 cron 表达式");
      return;
    }
    const form = new FormData();
    form.append("knowledgeId", knowledgeId.trim());
    if (isFileSource && file) {
      form.append("file", file);
    }
    if (isUrlSource) {
      form.append("url", url.trim());
      form.append("scheduleEnabled", String(scheduleEnabled));
      if (scheduleEnabled && scheduleCron.trim()) {
        form.append("scheduleCron", scheduleCron.trim());
      }
    }
    const result = await actionReq.runAction(() => apiPostForm<void>("/api/rag/document/upload", form), {
      successToast: "上传文档成功",
      errorFallback: "上传失败",
      onError: setMsg
    });
    if (result.ok) {
      setMsg("");
      setFile(null);
      setUrl("");
      setScheduleEnabled(false);
      setScheduleCron("");
      setSourceType("file");
      setShowUpload(false);
      await load();
      // 上传成功后启动轮询，持续跟踪处理进度
      startPolling();
    }
  };

  const remove = async (id: number) => {
    const result = await actionReq.runAction(() => apiDelete<void>(`/api/rag/document/${id}`), {
      successToast: "删除文档成功",
      errorFallback: "删除失败",
      onError: setMsg
    });
    if (result.ok) {
      await load();
    }
  };

  const statusLabel = (status?: number, desc?: string) => {
    if (status === 2) return <span className="admin-status admin-status-success">● 已完成</span>;
    if (status === 1) return <span className="admin-status admin-status-pending"><SpinnerIcon /> 处理中</span>;
    if (status === 0) return <span className="admin-status admin-status-pending">● 待处理</span>;
    if (status === -1) return <span className="admin-status admin-status-error">● 失败</span>;
    if (desc) return <span className="admin-status">{desc}</span>;
    return <span className="admin-status admin-status-unknown">未知</span>;
  };

  const formatTime = (t?: string) => {
    if (!t) return "-";
    try {
      const d = new Date(t);
      if (isNaN(d.getTime())) return t;
      return d.toLocaleString("zh-CN", {
        year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit"
      });
    } catch {
      return t;
    }
  };

  const knowledgeOptions: SelectOption[] = knowledgeItems.length === 0
    ? [{ value: "", label: knowledgeReq.loading ? "知识库加载中..." : "暂无可用知识库", disabled: true }]
    : knowledgeItems.map((item) => ({ value: String(item.id), label: item.name }));

  const filterKnowledgeOptions: SelectOption[] = [
    { value: "", label: "全部知识库" },
    ...knowledgeItems.map((item) => ({ value: String(item.id), label: item.name }))
  ];

  const sourceOptions: SelectOption[] = [
    { value: "file", label: "文件上传" },
    { value: "url", label: "URL 拉取" }
  ];

  const scheduleOptions: SelectOption[] = [
    { value: "off", label: "关闭" },
    { value: "on", label: "开启" }
  ];

  // Build knowledge name map for display
  const knowledgeNameMap = new Map<number, string>();
  knowledgeItems.forEach((item) => knowledgeNameMap.set(item.id, item.name));

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">文档管理</h1>
          <p className="admin-page-desc">上传与维护文档，系统将用于分段、索引与召回</p>
        </div>
        <div className="admin-page-actions">
          <button className="admin-btn admin-btn-primary" onClick={() => setShowUpload(true)}>
            <UploadIcon /> 上传文档
          </button>
        </div>
      </div>

      {/* Upload Dialog */}
      {showUpload && (
        <div className="admin-upload-modal" role="dialog" aria-modal="true" aria-label="上传文档弹窗">
          <div className="admin-upload-modal-backdrop" onClick={() => setShowUpload(false)} />
          <div className="admin-upload-modal-panel">
            <div className="admin-upload-modal-header">
              <div>
                <h3 className="admin-card-title">上传文档</h3>
                <p className="admin-card-desc">支持本地文件或远程 URL，上传后将自动入库处理</p>
              </div>
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={() => setShowUpload(false)}>关闭</button>
            </div>
            <div className="admin-upload-modal-body">
              <div className="admin-form-grid admin-form-grid-2">
                <div className="admin-form-group">
                  <label className="admin-label">知识库 *</label>
                  <CustomSelect
                    value={knowledgeId}
                    options={knowledgeOptions}
                    onChange={setKnowledgeId}
                    disabled={knowledgeReq.loading || knowledgeItems.length === 0}
                    placeholder="选择知识库"
                  />
                </div>
                <div className="admin-form-group">
                  <label className="admin-label">来源类型 *</label>
                  <CustomSelect
                    value={sourceType}
                    options={sourceOptions}
                    onChange={(v) => {
                      const next = v === "url" ? "url" : "file";
                      setSourceType(next);
                      if (next === "file") {
                        setUrl("");
                        setScheduleEnabled(false);
                        setScheduleCron("");
                      } else {
                        setFile(null);
                      }
                      setMsg("");
                    }}
                  />
                </div>
                {isFileSource && (
                  <div className="admin-form-group admin-form-group-span2">
                    <label className="admin-label">本地文件 *</label>
                    <div className="admin-file-picker">
                      <label className="admin-file-picker-trigger">
                        选择文件
                        <input type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} />
                      </label>
                      <span className="admin-file-picker-name">{file?.name || "未选择任何文件"}</span>
                    </div>
                  </div>
                )}
                {isUrlSource && (
                  <>
                    <div className="admin-form-group admin-form-group-span2">
                      <label className="admin-label">URL *</label>
                      <input
                        className="admin-input"
                        value={url}
                        onChange={(e) => setUrl(e.target.value)}
                        placeholder="https://example.com/doc.pdf"
                      />
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">定时更新</label>
                      <CustomSelect
                        value={scheduleEnabled ? "on" : "off"}
                        options={scheduleOptions}
                        onChange={(v) => {
                          const enabled = v === "on";
                          setScheduleEnabled(enabled);
                          if (enabled && !scheduleCron.trim()) {
                            setScheduleCron("0 0/30 * * * *");
                          }
                        }}
                      />
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">Cron 表达式 {scheduleEnabled ? "*" : ""}</label>
                      <input
                        className="admin-input"
                        value={scheduleCron}
                        disabled={!scheduleEnabled}
                        onChange={(e) => setScheduleCron(e.target.value)}
                        placeholder="0 0/30 * * * *"
                      />
                    </div>
                    <div className="admin-form-group admin-form-group-span2">
                      <span className="admin-muted">示例：0 0/30 * * * * 表示每 30 分钟执行一次</span>
                    </div>
                  </>
                )}
              </div>
            </div>
            <div className="admin-upload-modal-footer">
              <button className="admin-btn admin-btn-ghost" onClick={() => setShowUpload(false)} disabled={actionReq.loading}>
                取消
              </button>
              <button
                className="admin-btn admin-btn-primary"
                onClick={upload}
                disabled={
                  actionReq.loading ||
                  !knowledgeId.trim() ||
                  knowledgeItems.length === 0 ||
                  (isFileSource && !file) ||
                  (isUrlSource && !url.trim()) ||
                  (isUrlSource && scheduleEnabled && !scheduleCron.trim())
                }
              >
                {actionReq.loading ? "上传中..." : "开始上传"}
              </button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className={`admin-alert ${msg.includes("成功") ? "admin-alert-success" : "admin-alert-error"}`}>{msg}</div>}
      {!msg && listReq.error && <div className="admin-alert admin-alert-error">{listReq.error}</div>}

      {/* Filter Bar */}
      <div className="doc-filter-bar">
        <div className="doc-filter-select">
          <CustomSelect
            value={filterKnowledgeId}
            options={filterKnowledgeOptions}
            onChange={handleFilterChange}
            placeholder="全部知识库"
          />
        </div>
        <div className="doc-search-box">
          <SearchIcon />
          <input
            className="doc-search-input"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={handleSearchKeyDown}
            placeholder="搜索文档名称..."
          />
          {searchInput && (
            <button className="doc-search-clear" onClick={handleClearSearch} title="清除搜索">
              <ClearIcon />
            </button>
          )}
          <button className="doc-search-btn" onClick={handleSearch} disabled={listReq.loading}>
            搜索
          </button>
        </div>
      </div>

      {/* Document List */}
      {items.length > 0 ? (
        <div className="admin-card">
          <div className="admin-card-header">
            <h3 className="admin-card-title">文档列表</h3>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              {polling && <span className="admin-status admin-status-pending" style={{ fontSize: 12 }}><SpinnerIcon /> 自动刷新中...</span>}
              <span className="admin-badge">共 {items.length} 条</span>
            </div>
          </div>
          <div className="admin-card-body" style={{ padding: 0 }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>文档</th>
                  <th style={{ width: 140 }}>知识库</th>
                  <th style={{ width: 80 }}>Chunks</th>
                  <th style={{ width: 160 }}>上传时间</th>
                  <th style={{ width: 80 }}>状态</th>
                  <th style={{ width: 80 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <div className="admin-doc-cell">
                        <DocIcon />
                        <div>
                          <div className="admin-doc-name">{item.originalFileName || item.fileName || "未命名"}</div>
                          <div className="admin-doc-id">ID: {item.id}</div>
                        </div>
                      </div>
                    </td>
                    <td>
                      <span className="doc-knowledge-tag">
                        {knowledgeNameMap.get(item.knowledgeId!) || `ID: ${item.knowledgeId ?? "-"}`}
                      </span>
                    </td>
                    <td>
                      <span className="admin-table-num">{item.chunkCount ?? 0}</span>
                    </td>
                    <td>
                      <span className="doc-time">{formatTime(item.createTime)}</span>
                    </td>
                    <td>{statusLabel(item.processingStatus, item.processingStatusDesc)}</td>
                    <td>
                      <button className="admin-btn admin-btn-danger admin-btn-sm" onClick={() => void remove(item.id)} disabled={actionReq.loading}>
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        !listReq.loading && (
          <div className="admin-empty">
            <div className="admin-empty-icon">📄</div>
            <p>{searchName || filterKnowledgeId ? "未找到匹配的文档" : "暂无文档，点击上方按钮上传"}</p>
          </div>
        )
      )}
    </div>
  );
}

/* ===== SVG Icons ===== */

function UploadIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" y1="3" x2="12" y2="15" />
    </svg>
  );
}

function DocIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><polyline points="14 2 14 8 20 8" />
      <line x1="16" y1="13" x2="8" y2="13" /><line x1="16" y1="17" x2="8" y2="17" /><polyline points="10 9 9 9 8 9" />
    </svg>
  );
}

function SearchIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
    </svg>
  );
}

function ClearIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  );
}

function SpinnerIcon() {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      style={{ animation: "spin 1s linear infinite", display: "inline-block", verticalAlign: "middle", marginRight: 2 }}
    >
      <path d="M21 12a9 9 0 1 1-6.219-8.56" />
    </svg>
  );
}
