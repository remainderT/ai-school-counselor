import { useState } from "react";
import { apiGet } from "../lib/api";
import type { RetrievalMatch } from "../types";
import { useActionRequest } from "../hooks/useActionRequest";

export function SearchPanel() {
  const [query, setQuery] = useState("");
  const [userId, setUserId] = useState("anonymous");
  const [topK, setTopK] = useState(6);
  const [items, setItems] = useState<RetrievalMatch[]>([]);
  const req = useActionRequest();

  const run = async () => {
    if (!query.trim()) return;
    const q = new URLSearchParams({
      query: query.trim(),
      topK: String(topK),
      userId: userId.trim() || "anonymous"
    });
    const result = await req.runAction(() => apiGet<RetrievalMatch[]>(`/api/rag/chat/search?${q.toString()}`), {
      errorFallback: "检索失败"
    });
    if (result.ok) {
      setItems(result.data || []);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void run();
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">检索调试</h1>
          <p className="admin-page-desc">调试向量检索效果，验证召回质量与分数分布</p>
        </div>
      </div>

      {/* Search Form Card */}
      <div className="admin-card">
        <div className="admin-card-header">
          <h3 className="admin-card-title">检索参数</h3>
          <p className="admin-card-desc">输入查询内容，调整参数后执行检索</p>
        </div>
        <div className="admin-card-body">
          <div className="admin-form-grid admin-form-grid-3">
            <div className="admin-form-group admin-form-group-span2">
              <label className="admin-label">Query</label>
              <input
                className="admin-input"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="输入检索查询内容..."
              />
            </div>
            <div className="admin-form-row-2">
              <div className="admin-form-group">
                <label className="admin-label">TopK</label>
                <input
                  className="admin-input"
                  type="number"
                  value={topK}
                  onChange={(e) => setTopK(Number(e.target.value || 1))}
                />
              </div>
              <div className="admin-form-group">
                <label className="admin-label">UserId</label>
                <input
                  className="admin-input"
                  value={userId}
                  onChange={(e) => setUserId(e.target.value)}
                />
              </div>
            </div>
          </div>
          <div className="admin-form-actions">
            <button className="admin-btn admin-btn-primary" disabled={req.loading || !query.trim()} onClick={run}>
              {req.loading ? "检索中..." : "执行检索"}
            </button>
            <button className="admin-btn admin-btn-ghost" onClick={() => setItems([])}>清空结果</button>
          </div>
        </div>
      </div>

      {req.error && <div className="admin-alert admin-alert-error">{req.error}</div>}

      {/* Results */}
      {items.length > 0 && (
        <div className="admin-card">
          <div className="admin-card-header">
            <h3 className="admin-card-title">检索结果</h3>
            <span className="admin-badge">共 {items.length} 条</span>
          </div>
          <div className="admin-card-body" style={{ padding: 0 }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th style={{ width: 50 }}>#</th>
                  <th style={{ width: 200 }}>来源文件</th>
                  <th style={{ width: 100 }}>相关度</th>
                  <th>文本内容</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item, idx) => (
                  <tr key={`${item.chunkId ?? idx}-${idx}`}>
                    <td className="admin-table-num">{idx + 1}</td>
                    <td>
                      <span className="admin-tag">{item.sourceFileName || "未知来源"}</span>
                    </td>
                    <td>
                      <span className="admin-score">{item.relevanceScore?.toFixed(3) ?? "-"}</span>
                    </td>
                    <td className="admin-table-text">{item.textContent || ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {items.length === 0 && !req.loading && !req.error && (
        <div className="admin-empty">
          <div className="admin-empty-icon">🔍</div>
          <p>输入查询内容并执行检索，结果将在此展示</p>
        </div>
      )}
    </div>
  );
}
