import { useEffect, useRef, useState } from "react";
import { apiGet, toErrorMessage } from "../lib/api";

// ===== 类型定义 =====

interface TraceRecordVO {
  traceId: string;
  traceName?: string | null;
  conversationId?: string | null;
  taskId?: string | null;
  userId?: string | null;
  username?: string | null;
  status?: string | null;
  durationMs?: number | null;
  startTime?: string | null;
  endTime?: string | null;
  errorMessage?: string | null;
}

interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  pages: number;
  size: number;
}

interface TraceListPanelProps {
  onOpenTrace: (traceId: string) => void;
}

// ===== 工具函数 =====

function normalizeStatus(status?: string | null): "success" | "failed" | "running" | null {
  if (!status) return null;
  const s = status.toLowerCase();
  if (s === "success" || s === "completed" || s === "done") return "success";
  if (s === "failed" || s === "error" || s === "fail") return "failed";
  if (s === "running" || s === "in_progress" || s === "pending") return "running";
  return null;
}

function statusLabel(status?: string | null): string {
  const s = normalizeStatus(status);
  if (s === "success") return "SUCCESS";
  if (s === "failed") return "FAILED";
  if (s === "running") return "RUNNING";
  return status || "-";
}

function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "-";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)}s`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatDateTime(iso?: string | null): string {
  if (!iso) return "-";
  try {
    return new Date(iso).toLocaleString("zh-CN", {
      month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit"
    });
  } catch {
    return iso;
  }
}

function percentile(arr: number[], p: number): number {
  if (!arr.length) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const idx = Math.floor(sorted.length * p);
  return sorted[Math.min(idx, sorted.length - 1)];
}

const PAGE_SIZE = 15;

// ===== Inline SVG Icons =====

const ActivityIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M22 12h-4l-3 9L9 3l-3 9H2"/>
  </svg>
);

const ChevronIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="9 18 15 12 9 6" />
  </svg>
);

// ===== 主组件 =====

export function TraceListPanel({ onOpenTrace }: TraceListPanelProps) {
  const requestRef = useRef(0);
  const [filterInput, setFilterInput] = useState("");
  const [activeFilter, setActiveFilter] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [pageData, setPageData] = useState<PageResult<TraceRecordVO> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const runs = pageData?.records ?? [];

  const loadRuns = async (current = pageNo, traceId = activeFilter) => {
    const reqId = ++requestRef.current;
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ current: String(current), size: String(PAGE_SIZE) });
      if (traceId.trim()) params.set("traceId", traceId.trim());
      const data = await apiGet<PageResult<TraceRecordVO>>(`/api/rag/traces/runs?${params}`);
      if (requestRef.current !== reqId) return;
      setPageData(data);
    } catch (e) {
      if (requestRef.current !== reqId) return;
      setError(toErrorMessage(e, "加载链路运行列表失败"));
    } finally {
      if (requestRef.current !== reqId) return;
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadRuns(pageNo, activeFilter);
  }, [pageNo, activeFilter]);

  const handleSearch = () => {
    setPageNo(1);
    setActiveFilter(filterInput.trim());
  };

  const handleRefresh = () => void loadRuns(pageNo, activeFilter);

  // 统计
  const durations = runs.map((r) => Number(r.durationMs ?? 0)).filter((v) => Number.isFinite(v) && v > 0);
  const successCount = runs.filter((r) => normalizeStatus(r.status) === "success").length;
  const failedCount = runs.filter((r) => normalizeStatus(r.status) === "failed").length;
  const runningCount = runs.filter((r) => normalizeStatus(r.status) === "running").length;
  const avgDuration = durations.length ? Math.round(durations.reduce((s, v) => s + v, 0) / durations.length) : 0;
  const p95Duration = Math.round(percentile(durations, 0.95));
  const successRate = runs.length ? Math.round((successCount / runs.length) * 1000) / 10 : 0;

  const currentPage = pageData?.current ?? pageNo;
  const totalPages = pageData?.pages ?? 1;
  const total = pageData?.total ?? 0;

  return (
    <div className="admin-page admin-page-shell trc-list-page">
      <section className="admin-page-hero admin-page-hero-trace">
        <div className="trc-page-header">
          <div className="trc-header-left">
            <h1 className="trc-page-title">
              <span className="trc-title-icon"><ActivityIcon /></span>
              智能问答链路观测
            </h1>
            <p className="trc-page-subtitle">实时监控每次对话的全链路执行过程，快速定位性能瓶颈与异常节点</p>
          </div>
          <div className="trc-header-actions">
            <div className="trc-search-box">
              <svg className="trc-search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <input
                className="trc-search-input"
                value={filterInput}
                onChange={(e) => setFilterInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") handleSearch(); }}
                placeholder="输入 Trace ID 搜索..."
              />
              <button className="trc-search-btn" onClick={handleSearch} disabled={loading}>查询</button>
            </div>
            <button className="trc-refresh-btn" onClick={handleRefresh} disabled={loading} title="刷新数据">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="23 4 23 10 17 10" /><polyline points="1 20 1 14 7 14" />
                <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
              </svg>
            </button>
          </div>
        </div>

        <div className="trc-metric-grid">
          <div className="trc-metric-card">
            <span className="trc-metric-label">总链路数</span>
            <span className="trc-metric-main">{total.toLocaleString("zh-CN")}</span>
            <span className="trc-metric-sub">当前页 {runs.length} 条</span>
          </div>
          <div className="trc-metric-card">
            <span className="trc-metric-label">成功率</span>
            <span className="trc-metric-main trc-val-accent">{successRate}%</span>
            <span className="trc-metric-sub">成功 {successCount} / 失败 {failedCount}</span>
          </div>
          <div className="trc-metric-card">
            <span className="trc-metric-label">运行中</span>
            <span className="trc-metric-main">{runningCount}</span>
            <span className="trc-metric-sub">实时监控活跃链路</span>
          </div>
          <div className="trc-metric-card">
            <span className="trc-metric-label">性能概览</span>
            <span className="trc-metric-main">{formatDuration(avgDuration)}</span>
            <span className="trc-metric-sub">P95 {formatDuration(p95Duration)}</span>
          </div>
        </div>
      </section>

      {error && <div className="admin-alert admin-alert-error">{error}</div>}

      <div className="admin-card trc-record-card">
        <div className="admin-card-header admin-card-header-rich">
          <div>
            <h3 className="admin-card-title">链路列表</h3>
            <p className="admin-card-desc">点击任意链路可查看节点瀑布图、耗时分布和异常信息</p>
          </div>
          <span className="admin-badge">第 {currentPage}/{totalPages} 页</span>
        </div>

        <div className="admin-card-body trc-record-card-body">
          <div className="trc-metric-strip">
            <div className="trc-metric-item">
              <span className="trc-metric-dot trc-dot-success" />
              <span className="trc-metric-label">成功</span>
              <span className="trc-metric-val">{successCount}</span>
            </div>
            <span className="trc-metric-sep" />
            <div className="trc-metric-item">
              <span className="trc-metric-dot trc-dot-failed" />
              <span className="trc-metric-label">失败</span>
              <span className="trc-metric-val">{failedCount}</span>
            </div>
            <span className="trc-metric-sep" />
            <div className="trc-metric-item">
              <span className="trc-metric-dot trc-dot-running" />
              <span className="trc-metric-label">运行中</span>
              <span className="trc-metric-val">{runningCount}</span>
            </div>
            <span className="trc-metric-sep" />
            <div className="trc-metric-item">
              <span className="trc-metric-label">Avg</span>
              <span className="trc-metric-val">{formatDuration(avgDuration)}</span>
            </div>
            <span className="trc-metric-sep" />
            <div className="trc-metric-item">
              <span className="trc-metric-label">P95</span>
              <span className="trc-metric-val">{formatDuration(p95Duration)}</span>
            </div>
          </div>

          <div className="trc-card-list">
            {loading && runs.length === 0 ? (
              <div className="admin-empty trc-empty-state"><p>加载中...</p></div>
            ) : !loading && runs.length === 0 ? (
              <div className="admin-empty trc-empty-state">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#c0cad8" strokeWidth="1.5"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
                <h3 className="admin-empty-title">暂无链路数据</h3>
                <p className="admin-empty-desc">发起一次对话后即可查看完整链路执行过程和性能数据。</p>
              </div>
            ) : (
              runs.map((run, idx) => {
                const ns = normalizeStatus(run.status);
                const dotCls = ns === "success" ? "trc-dot-success" : ns === "failed" ? "trc-dot-failed" : ns === "running" ? "trc-dot-running" : "";
                const statusCls = ns === "success" ? "trc-tag-success" : ns === "failed" ? "trc-tag-failed" : ns === "running" ? "trc-tag-running" : "trc-tag-default";
                return (
                  <div key={run.traceId} className="trc-card" onClick={() => onOpenTrace(run.traceId)}>
                    <div className="trc-card-left">
                      <span className="trc-card-index">{(currentPage - 1) * PAGE_SIZE + idx + 1}</span>
                      <span className={`trc-card-dot ${dotCls}`} />
                    </div>
                    <div className="trc-card-body">
                      <div className="trc-card-row1">
                        <span className="trc-card-name">{run.traceName || "stream-chat"}</span>
                        <span className={`trc-card-status ${statusCls}`}>{statusLabel(run.status)}</span>
                      </div>
                      <div className="trc-card-row2">
                        <code className="trc-card-id">{run.traceId.length > 16 ? `${run.traceId.slice(0, 8)}...${run.traceId.slice(-4)}` : run.traceId}</code>
                        <span className="trc-card-duration">{formatDuration(run.durationMs)}</span>
                        <span className="trc-card-time">{formatDateTime(run.startTime)}</span>
                        {(run.username || run.userId) && <span className="trc-card-user">{run.username || run.userId}</span>}
                      </div>
                      {run.errorMessage && <div className="trc-card-error">{run.errorMessage}</div>}
                    </div>
                    <div className="trc-card-arrow"><ChevronIcon /></div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {total > 0 && (
          <div className="trc-pagination trc-pagination-card">
            <span className="trc-pagination-info">第 {currentPage} / {totalPages} 页</span>
            <div className="trc-pagination-btns">
              <button className="trc-page-btn" disabled={currentPage <= 1 || loading} onClick={() => setPageNo((p) => Math.max(1, p - 1))}>上一页</button>
              <button className="trc-page-btn" disabled={currentPage >= totalPages || loading} onClick={() => setPageNo((p) => p + 1)}>下一页</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
