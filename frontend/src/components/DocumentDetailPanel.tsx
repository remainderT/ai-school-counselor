import { useCallback, useEffect, useRef, useState } from "react";
import { apiAuthHeaders, apiDelete, apiGet, apiPut, apiUrl } from "../lib/api";
import { useActionRequest } from "../hooks/useActionRequest";
import type { ChunkItem, DocumentDetailItem, PageResponse } from "../types";

interface DocumentDetailPanelProps {
  documentId: number;
  onBack: () => void;
}

const BackIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 12H5" />
    <polyline points="12 19 5 12 12 5" />
  </svg>
);

const ExpandIcon = ({ expanded }: { expanded: boolean }) => (
  <svg
    width="16"
    height="16"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    style={{
      transition: "transform 150ms ease",
      transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
    }}
  >
    <polyline points="9 6 15 12 9 18" />
  </svg>
);

const CHUNK_PAGE_SIZE = 10;

/* ---- 编辑弹窗 ---- */
function ChunkEditModal({
  chunk,
  onClose,
  onSave,
}: {
  chunk: ChunkItem;
  onClose: () => void;
  onSave: (text: string) => void;
}) {
  const [text, setText] = useState(chunk.textData || "");
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    textareaRef.current?.focus();
  }, []);

  return (
    <div className="chunk-modal-overlay" onClick={onClose}>
      <div className="chunk-modal" onClick={(e) => e.stopPropagation()}>
        <div className="chunk-modal-header">
          <h3 className="chunk-modal-title">编辑 Chunk #{chunk.fragmentIndex ?? 0}</h3>
          <button type="button" className="chunk-modal-close" onClick={onClose}>
            ×
          </button>
        </div>
        <div className="chunk-modal-body">
          <textarea
            ref={textareaRef}
            className="chunk-modal-textarea"
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="输入 chunk 文本内容..."
          />
          <div className="chunk-modal-stats">
            <span>字符数: {text.length.toLocaleString()}</span>
          </div>
        </div>
        <div className="chunk-modal-footer">
          <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={onClose}>
            取消
          </button>
          <button
            className="admin-btn admin-btn-primary admin-btn-sm"
            onClick={() => onSave(text)}
            disabled={!text.trim()}
          >
            保存
          </button>
        </div>
      </div>
    </div>
  );
}

export function DocumentDetailPanel({ documentId, onBack }: DocumentDetailPanelProps) {
  const [detail, setDetail] = useState<DocumentDetailItem | null>(null);
  const [chunks, setChunks] = useState<ChunkItem[]>([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [expandedChunkId, setExpandedChunkId] = useState<number | null>(null);
  const [editingChunk, setEditingChunk] = useState<ChunkItem | null>(null);
  const req = useActionRequest();

  const toggleChunk = (id: number) => {
    setExpandedChunkId((current) => (current === id ? null : id));
  };

  const download = useCallback(async () => {
    const url = apiUrl(`/api/rag/document/${documentId}/download`);
    const headers = apiAuthHeaders();
    const resp = await fetch(url, {
      method: "GET",
      headers,
    });
    const contentType = resp.headers.get("content-type") || "";
    if (!resp.ok || contentType.includes("application/json")) {
      let message = `下载失败: HTTP ${resp.status}`;
      try {
        const payload = (await resp.json()) as { message?: string };
        if (payload?.message) {
          message = payload.message;
        }
      } catch {
        // ignore
      }
      throw new Error(message);
    }
    const blob = await resp.blob();
    const objectUrl = window.URL.createObjectURL(blob);
    const disposition = resp.headers.get("content-disposition") || "";
    const match = disposition.match(/filename\*=UTF-8''([^;]+)|filename="?([^\";]+)"?/i);
    const fallbackName = detail?.originalFileName || `document-${documentId}`;
    const filename = decodeURIComponent(match?.[1] || match?.[2] || fallbackName);
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(objectUrl);
  }, [detail?.originalFileName, documentId]);

  const loadChunks = useCallback(
    async (nextPage?: number, nextQuery?: string) => {
      const currentPage = nextPage ?? page;
      const keyword = nextQuery ?? query;
      const params = new URLSearchParams({
        current: String(currentPage),
        size: String(CHUNK_PAGE_SIZE),
      });
      if (keyword.trim()) {
        params.set("keyword", keyword.trim());
      }
      const result = await req.runAction(
        () =>
          apiGet<PageResponse<ChunkItem>>(
            `/api/rag/document/${documentId}/chunks/page?${params.toString()}`
          ),
        { errorFallback: "Chunk 内容加载失败" }
      );
      if (result.ok) {
        const data = result.data;
        setChunks(data?.records || []);
        setTotal(data?.total || 0);
        setTotalPages(Math.max(1, data?.pages || 1));
        setPage(Number(data?.current || currentPage));
      }
    },
    [documentId, page, query, req]
  );

  /* ---- chunk 操作 ---- */
  const handleDeleteChunk = useCallback(
    async (chunk: ChunkItem) => {
      if (!window.confirm(`确定删除 Chunk #${chunk.fragmentIndex ?? 0} 吗？此操作不可恢复。`)) {
        return;
      }
      const result = await req.runAction(
        () => apiDelete<void>(`/api/rag/document/${documentId}/chunks/${chunk.id}`),
        { errorFallback: "删除 Chunk 失败" }
      );
      if (result.ok) {
        void loadChunks(page, query);
      }
    },
    [documentId, loadChunks, page, query, req]
  );

  const handleToggleEnabled = useCallback(
    async (chunk: ChunkItem) => {
      const nextEnabled = chunk.enabled === 1 ? false : true;
      const result = await req.runAction(
        () =>
          apiPut<void>(
            `/api/rag/document/${documentId}/chunks/${chunk.id}/toggle?enabled=${nextEnabled}`,
            {}
          ),
        { errorFallback: "切换状态失败" }
      );
      if (result.ok) {
        // 本地更新，避免重新请求
        setChunks((prev) =>
          prev.map((c) => (c.id === chunk.id ? { ...c, enabled: nextEnabled ? 1 : 0 } : c))
        );
      }
    },
    [documentId, req]
  );

  const handleSaveEdit = useCallback(
    async (text: string) => {
      if (!editingChunk) return;
      const result = await req.runAction(
        () =>
          apiPut<void>(`/api/rag/document/${documentId}/chunks/${editingChunk.id}`, {
            textData: text,
          }),
        { errorFallback: "编辑 Chunk 失败" }
      );
      if (result.ok) {
        setEditingChunk(null);
        void loadChunks(page, query);
      }
    },
    [documentId, editingChunk, loadChunks, page, query, req]
  );

  useEffect(() => {
    setQuery("");
    setPage(1);
    setChunks([]);
    setTotal(0);
    setTotalPages(1);
    setDetail(null);
    void req
      .runAction(() => apiGet<DocumentDetailItem>(`/api/rag/document/${documentId}/detail`), {
        errorFallback: "文档详情加载失败",
      })
      .then((result) => {
        if (result.ok) {
          setDetail(result.data ?? null);
        }
      });
    void loadChunks(1, "");
  }, [documentId]);

  const safePage = Math.min(page, totalPages);

  return (
    <div className="admin-page doc-detail-page">
      <div className="admin-page-header">
        <div>
          <button
            type="button"
            className="admin-btn admin-btn-ghost admin-btn-sm doc-detail-back"
            onClick={onBack}
          >
            <BackIcon /> 返回文档列表
          </button>
          <h1 className="admin-page-title">
            {detail?.originalFileName || `文档 #${documentId}`}
          </h1>
        </div>
      </div>

      {req.error && <div className="admin-alert admin-alert-error">{req.error}</div>}

      {!detail && req.loading && (
        <div className="admin-card">
          <div className="admin-card-body">
            <div className="admin-empty" style={{ padding: "48px 24px" }}>
              <p>文档详情加载中...</p>
            </div>
          </div>
        </div>
      )}

      {detail && (
        <div className="admin-card">
          <div className="admin-card-header">
            <div>
              <h3 className="admin-card-title">Chunk 内容</h3>
            </div>
            <div className="doc-detail-head-actions">
              <button
                className="admin-btn admin-btn-ghost admin-btn-sm"
                onClick={() => void download()}
              >
                下载文档
              </button>
              <span className="admin-badge">共 {detail.chunkCount ?? total} 个 chunk</span>
            </div>
          </div>
          <div className="admin-card-body">
            <div className="doc-detail-toolbar">
              <input
                className="admin-input doc-detail-search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    void loadChunks(1, query);
                  }
                }}
                placeholder="搜索 chunk 内容..."
              />
              <button
                className="admin-btn admin-btn-primary admin-btn-sm"
                onClick={() => void loadChunks(1, query)}
                disabled={req.loading}
              >
                搜索
              </button>
              <div className="doc-detail-toolbar-meta">
                <span>
                  第 {safePage} / {totalPages} 页
                </span>
                <span>每页 {CHUNK_PAGE_SIZE} 条</span>
                <span>共 {total} 条</span>
              </div>
            </div>

            {total === 0 ? (
              <div className="admin-empty" style={{ padding: "40px 24px" }}>
                <p>
                  {query.trim()
                    ? "没有匹配的 chunk 内容"
                    : "当前文档还没有可展示的 chunk"}
                </p>
              </div>
            ) : (
              <>
                {/* 表格式 chunk 列表 */}
                <div className="chunk-table-wrap">
                  <table className="chunk-table">
                    <thead>
                      <tr>
                        <th className="chunk-col-idx">#</th>
                        <th className="chunk-col-content">内容摘要</th>
                        <th className="chunk-col-status">状态</th>
                        <th className="chunk-col-chars">字符数</th>
                        <th className="chunk-col-tokens">Token 数</th>
                        <th className="chunk-col-actions">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {chunks.map((chunk) => {
                        const expanded = expandedChunkId === chunk.id;
                        const charCount = chunk.textData?.length ?? 0;
                        const isEnabled = chunk.enabled !== 0;
                        const preview = chunk.textData
                          ? chunk.textData.length > 120
                            ? chunk.textData.slice(0, 120) + "..."
                            : chunk.textData
                          : "—";
                        return (
                          <tr
                            key={chunk.id}
                            className={`chunk-table-row${expanded ? " chunk-row-expanded" : ""}${!isEnabled ? " chunk-row-disabled" : ""}`}
                          >
                            <td className="chunk-col-idx">
                              <span className="chunk-idx-badge">
                                {chunk.fragmentIndex ?? 0}
                              </span>
                            </td>
                            <td className="chunk-col-content">
                              <div className="chunk-content-cell">
                                <div
                                  className="chunk-preview-text"
                                  onClick={() => toggleChunk(chunk.id)}
                                  title="点击展开/收起全文"
                                >
                                  {preview}
                                </div>
                                {expanded && (
                                  <div className="chunk-expanded-body">
                                    <pre className="chunk-full-text">
                                      {chunk.textData || "暂无内容"}
                                    </pre>
                                  </div>
                                )}
                              </div>
                            </td>
                            <td className="chunk-col-status">
                              <span
                                className={`chunk-status-tag${isEnabled ? " chunk-status-on" : " chunk-status-off"}`}
                              >
                                {isEnabled ? "启用" : "禁用"}
                              </span>
                            </td>
                            <td className="chunk-col-chars">
                              <span className="chunk-stat-pill">
                                {charCount.toLocaleString()}
                              </span>
                            </td>
                            <td className="chunk-col-tokens">
                              <span className="chunk-stat-pill">
                                {chunk.tokenEstimate?.toLocaleString() ?? "—"}
                              </span>
                            </td>
                            <td className="chunk-col-actions">
                              <div className="chunk-action-group">
                                <button
                                  type="button"
                                  className="chunk-action-btn chunk-action-expand"
                                  title={expanded ? "收起" : "展开"}
                                  onClick={() => toggleChunk(chunk.id)}
                                >
                                  <ExpandIcon expanded={expanded} />
                                </button>
                                <button
                                  type="button"
                                  className="chunk-action-btn chunk-action-edit"
                                  title="编辑"
                                  onClick={() => setEditingChunk(chunk)}
                                >
                                  编辑
                                </button>
                                <button
                                  type="button"
                                  className={`chunk-action-btn ${isEnabled ? "chunk-action-disable" : "chunk-action-enable"}`}
                                  title={isEnabled ? "禁用" : "启用"}
                                  onClick={() => void handleToggleEnabled(chunk)}
                                  disabled={req.loading}
                                >
                                  {isEnabled ? "禁用" : "启用"}
                                </button>
                                <button
                                  type="button"
                                  className="chunk-action-btn chunk-action-delete"
                                  title="删除"
                                  onClick={() => void handleDeleteChunk(chunk)}
                                  disabled={req.loading}
                                >
                                  删除
                                </button>
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>

                {total > CHUNK_PAGE_SIZE && (
                  <div className="admin-pagination doc-detail-pagination">
                    <button
                      className="admin-pagination-btn"
                      disabled={safePage <= 1 || req.loading}
                      onClick={() => void loadChunks(safePage - 1)}
                    >
                      上一页
                    </button>
                    <div className="admin-pagination-info">
                      <span>
                        第 {safePage} 页 / 共 {totalPages} 页
                      </span>
                      <span>当前展示 {chunks.length} 条</span>
                    </div>
                    <button
                      className="admin-pagination-btn"
                      disabled={safePage >= totalPages || req.loading}
                      onClick={() => void loadChunks(safePage + 1)}
                    >
                      下一页
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}

      {/* 编辑弹窗 */}
      {editingChunk && (
        <ChunkEditModal
          chunk={editingChunk}
          onClose={() => setEditingChunk(null)}
          onSave={(text) => void handleSaveEdit(text)}
        />
      )}
    </div>
  );
}
