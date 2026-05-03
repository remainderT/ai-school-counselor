import { useCallback, useEffect, useState } from "react";
import { apiAuthHeaders, apiGet, apiUrl } from "../lib/api";
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

const ChevronDownIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

const CHUNK_PAGE_SIZE = 10;

export function DocumentDetailPanel({ documentId, onBack }: DocumentDetailPanelProps) {
  const [detail, setDetail] = useState<DocumentDetailItem | null>(null);
  const [chunks, setChunks] = useState<ChunkItem[]>([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [expandedChunkId, setExpandedChunkId] = useState<number | null>(null);
  const req = useActionRequest();

  const toggleChunk = (id: number) => {
    setExpandedChunkId((current) => (current === id ? null : id));
  };

  const download = useCallback(async () => {
    const url = apiUrl(`/api/rag/document/${documentId}/download`);
    const headers = apiAuthHeaders();
    const resp = await fetch(url, {
      method: "GET",
      headers
    });
    const contentType = resp.headers.get("content-type") || "";
    if (!resp.ok || contentType.includes("application/json")) {
      let message = `下载失败: HTTP ${resp.status}`;
      try {
        const payload = await resp.json() as { message?: string };
        if (payload?.message) {
          message = payload.message;
        }
      } catch {
        // ignore json parse error and keep default message
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

  const loadChunks = useCallback(async (nextPage?: number, nextQuery?: string) => {
    const currentPage = nextPage ?? page;
    const keyword = nextQuery ?? query;
    const params = new URLSearchParams({
      current: String(currentPage),
      size: String(CHUNK_PAGE_SIZE)
    });
    if (keyword.trim()) {
      params.set("keyword", keyword.trim());
    }
    const result = await req.runAction(() => apiGet<PageResponse<ChunkItem>>(`/api/rag/document/${documentId}/chunks/page?${params.toString()}`), {
      errorFallback: "Chunk 内容加载失败"
    });
    if (result.ok) {
      const data = result.data;
      setChunks(data?.records || []);
      setTotal(data?.total || 0);
      setTotalPages(Math.max(1, data?.pages || 1));
      setPage(Number(data?.current || currentPage));
    }
  }, [documentId, page, query, req]);

  useEffect(() => {
    setQuery("");
    setPage(1);
    setChunks([]);
    setTotal(0);
    setTotalPages(1);
    setDetail(null);
    void req.runAction(() => apiGet<DocumentDetailItem>(`/api/rag/document/${documentId}/detail`), {
      errorFallback: "文档详情加载失败"
    }).then((result) => {
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
          <button type="button" className="admin-btn admin-btn-ghost admin-btn-sm doc-detail-back" onClick={onBack}>
            <BackIcon /> 返回文档列表
          </button>
          <h1 className="admin-page-title">{detail?.originalFileName || `文档 #${documentId}`}</h1>
          <p className="admin-page-desc">仅保留 chunk 内容区域，支持搜索与后端真实分页浏览。</p>
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
              <p className="admin-card-desc">按切片顺序展示文档内容，可搜索关键字并分页浏览。</p>
            </div>
            <div className="doc-detail-head-actions">
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={() => void download()}>
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
              <button className="admin-btn admin-btn-primary admin-btn-sm" onClick={() => void loadChunks(1, query)} disabled={req.loading}>
                搜索
              </button>
              <div className="doc-detail-toolbar-meta">
                <span>第 {safePage} / {totalPages} 页</span>
                <span>每页 {CHUNK_PAGE_SIZE} 条</span>
                <span>共 {total} 条</span>
              </div>
            </div>

            {total === 0 ? (
              <div className="admin-empty" style={{ padding: "40px 24px" }}>
                <p>{query.trim() ? "没有匹配的 chunk 内容" : "当前文档还没有可展示的 chunk"}</p>
              </div>
            ) : (
              <>
                <div className="doc-detail-chunk-list">
                  {chunks.map((chunk) => {
                    const expanded = expandedChunkId === chunk.id;
                    const panelId = `chunk-panel-${chunk.id}`;
                    return (
                      <article key={chunk.id} className={`doc-detail-chunk-card${expanded ? " expanded" : ""}`}>
                        <button
                          type="button"
                          className="doc-detail-chunk-trigger"
                          aria-expanded={expanded}
                          aria-controls={panelId}
                          onClick={() => toggleChunk(chunk.id)}
                        >
                          <div className="doc-detail-chunk-head">
                            <div className="doc-detail-chunk-title">Chunk #{chunk.fragmentIndex ?? 0}</div>
                            <div className="doc-detail-chunk-head-right">
                              <div className="doc-detail-chunk-meta">
                                <span>ID: {chunk.id}</span>
                                <span>Tokens: {chunk.tokenEstimate ?? "-"}</span>
                              </div>
                              <div className="doc-detail-chunk-arrow" aria-hidden="true"><ChevronDownIcon /></div>
                            </div>
                          </div>
                        </button>
                        {expanded && (
                          <div id={panelId} className="doc-detail-chunk-body">
                            <pre className="doc-detail-chunk-text">{chunk.textData || "暂无内容"}</pre>
                          </div>
                        )}
                      </article>
                    );
                  })}
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
                      <span>第 {safePage} 页 / 共 {totalPages} 页</span>
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
    </div>
  );
}
