import { useEffect, useState } from "react";
import { apiDelete, apiGet, apiPostForm } from "../lib/api";
import type { DocumentItem } from "../types";
import { useActionRequest } from "../hooks/useActionRequest";

const CRON_TEMPLATES = [
  { key: "every-30m", label: "每 30 分钟", value: "0 0/30 * * * *" },
  { key: "every-1h", label: "每 1 小时", value: "0 0 * * * *" },
  { key: "daily-2am", label: "每天凌晨 2 点", value: "0 0 2 * * *" },
  { key: "daily-8am", label: "每天早上 8 点", value: "0 0 8 * * *" },
  { key: "weekly-mon-9am", label: "每周一 9 点", value: "0 0 9 * * MON" }
] as const;

export function DocumentPanel() {
  const [items, setItems] = useState<DocumentItem[]>([]);
  const [knowledgeId, setKnowledgeId] = useState("");
  const [sourceType, setSourceType] = useState<"file" | "url">("file");
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState("");
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [scheduleCron, setScheduleCron] = useState("");
  const [cronTemplate, setCronTemplate] = useState("every-30m");
  const [showUpload, setShowUpload] = useState(false);
  const [msg, setMsg] = useState("");
  const listReq = useActionRequest();
  const actionReq = useActionRequest();

  const load = async () => {
    const result = await listReq.runAction(() => apiGet<DocumentItem[]>("/api/rag/document/list"), {
      errorFallback: "文档加载失败",
      onError: setMsg
    });
    if (result.ok) {
      setItems(result.data || []);
    }
  };

  useEffect(() => {
    void load();
  }, []);

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
      setCronTemplate("every-30m");
      setSourceType("file");
      setShowUpload(false);
      await load();
    }
  };

  const remove = async (id: string) => {
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
    if (status === 1) return <span className="admin-status admin-status-pending">● 处理中</span>;
    if (status === 0) return <span className="admin-status admin-status-pending">● 待处理</span>;
    if (status === -1) return <span className="admin-status admin-status-error">● 失败</span>;
    if (desc) return <span className="admin-status">{desc}</span>;
    return <span className="admin-status admin-status-unknown">未知</span>;
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">文档管理</h1>
          <p className="admin-page-desc">上传与维护文档，系统将用于分段、索引与召回</p>
        </div>
        <div className="admin-page-actions">
          <button className="admin-btn admin-btn-primary" onClick={() => setShowUpload(!showUpload)}>
            <UploadIcon /> 上传文档
          </button>
        </div>
      </div>

      {/* Upload Form */}
      {showUpload && (
        <div className="admin-card admin-card-highlight">
          <div className="admin-card-header">
            <h3 className="admin-card-title">上传文档</h3>
            <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={() => setShowUpload(false)}>取消</button>
          </div>
          <div className="admin-card-body">
            <div className="admin-form-grid admin-form-grid-2">
              <div className="admin-form-group">
                <label className="admin-label">知识库 ID *</label>
                <input className="admin-input" value={knowledgeId} onChange={(e) => setKnowledgeId(e.target.value)} placeholder="输入关联的知识库 ID" />
              </div>
              <div className="admin-form-group">
                <label className="admin-label">来源类型 *</label>
                <select
                  className="admin-select"
                  value={sourceType}
                  onChange={(e) => {
                    const next = e.target.value === "url" ? "url" : "file";
                    setSourceType(next);
                    if (next === "file") {
                      setUrl("");
                      setScheduleEnabled(false);
                      setScheduleCron("");
                      setCronTemplate("every-30m");
                    } else {
                      setFile(null);
                    }
                    setMsg("");
                  }}
                >
                  <option value="file">文件上传</option>
                  <option value="url">URL 拉取</option>
                </select>
              </div>
              {isFileSource && (
                <div className="admin-form-group admin-form-group-span2">
                  <label className="admin-label">文件 *</label>
                  <div className="admin-file-input">
                    <input type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} />
                    {file && <span className="admin-file-name">{file.name}</span>}
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
                    <select
                      className="admin-select"
                      value={scheduleEnabled ? "on" : "off"}
                      onChange={(e) => {
                        const enabled = e.target.value === "on";
                        setScheduleEnabled(enabled);
                        if (enabled && !scheduleCron.trim()) {
                          const defaultTemplate = CRON_TEMPLATES.find((item) => item.key === "every-30m");
                          if (defaultTemplate) {
                            setCronTemplate(defaultTemplate.key);
                            setScheduleCron(defaultTemplate.value);
                          }
                        }
                      }}
                    >
                      <option value="off">关闭</option>
                      <option value="on">开启</option>
                    </select>
                  </div>
                  <div className="admin-form-group">
                    <label className="admin-label">Cron 模板</label>
                    <select
                      className="admin-select"
                      disabled={!scheduleEnabled}
                      value={cronTemplate}
                      onChange={(e) => {
                        const selected = e.target.value;
                        setCronTemplate(selected);
                        const template = CRON_TEMPLATES.find((item) => item.key === selected);
                        if (template) {
                          setScheduleCron(template.value);
                        }
                      }}
                    >
                      {CRON_TEMPLATES.map((template) => (
                        <option key={template.key} value={template.key}>
                          {template.label}
                        </option>
                      ))}
                      <option value="custom">自定义</option>
                    </select>
                  </div>
                  <div className="admin-form-group admin-form-group-span2">
                    <label className="admin-label">Cron 表达式 {scheduleEnabled ? "*" : ""}</label>
                    <input
                      className="admin-input"
                      value={scheduleCron}
                      disabled={!scheduleEnabled}
                      onChange={(e) => {
                        setScheduleCron(e.target.value);
                        setCronTemplate("custom");
                      }}
                      placeholder="0 0/30 * * * *"
                    />
                  </div>
                  <div className="admin-form-group admin-form-group-span2">
                    <span className="admin-muted">示例：0 0/30 * * * * 表示每 30 分钟执行一次</span>
                  </div>
                </>
              )}
            </div>
            <div className="admin-form-actions">
              <button
                className="admin-btn admin-btn-primary"
                onClick={upload}
                disabled={
                  actionReq.loading ||
                  !knowledgeId.trim() ||
                  (isFileSource && !file) ||
                  (isUrlSource && !url.trim()) ||
                  (isUrlSource && scheduleEnabled && !scheduleCron.trim())
                }
              >
                {actionReq.loading ? "上传中..." : "上传"}
              </button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className={`admin-alert ${msg.includes("成功") ? "admin-alert-success" : "admin-alert-error"}`}>{msg}</div>}
      {!msg && listReq.error && <div className="admin-alert admin-alert-error">{listReq.error}</div>}

      {/* Document List */}
      {items.length > 0 ? (
        <div className="admin-card">
          <div className="admin-card-header">
            <h3 className="admin-card-title">文档列表</h3>
            <span className="admin-badge">共 {items.length} 条</span>
          </div>
          <div className="admin-card-body" style={{ padding: 0 }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>文档</th>
                  <th style={{ width: 100 }}>知识库 ID</th>
                  <th style={{ width: 100 }}>状态</th>
                  <th style={{ width: 100 }}>操作</th>
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
                    <td>{item.knowledgeId ?? "-"}</td>
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
            <p>暂无文档，点击上方按钮上传</p>
          </div>
        )
      )}
    </div>
  );
}

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
