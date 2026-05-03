import { useEffect, useRef, useState } from "react";
import { apiGet, toErrorMessage } from "../lib/api";

// ===== 类型定义 =====

interface RagTraceRunVO {
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
  if (s === "success") return "成功";
  if (s === "failed") return "失败";
  if (s === "running") return "运行中";
  return status || "-";
}

function statusClass(status?: string | null): string {
  const s = normalizeStatus(status);
  if (s === "success") return "trace-badge trace-badge-success";
  if (s === "failed") return "trace-badge trace-badge-failed";
  if (s === "running") return "trace-badge trace-badge-running";
  return "trace-badge trace-badge-default";
}

function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "-";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function formatDateTime(iso?: string | null): string {
  if (!iso) return "-";
  try {
    return new Date(iso).toLocaleString("zh-CN", {
      year: "numeric", month: "2-digit", day: "2-digit",
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

const PAGE_SIZE = 20;

// ===== Inline SVG Icons =====

const RefreshIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="23 4 23 10 17 10" />
    <polyline points="1 20 1 14 7 14" />
    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
  </svg>
);

const SearchIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="11" cy="11" r="8" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
  </svg>
);

const EyeIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

// ===== 统计卡片 =====

interface StatCardProps {
  title: string;
  value: string;
  unit?: string;
  color: "emerald" | "cyan" | "indigo" | "amber";
}

function StatCard({ title, value, unit, color }: StatCardProps) {
  return (
    <div className={`trace-stat-card trace-stat-card-${color}`}>
      <div className="trace-stat-value">
        {value}
        {unit && <span className="trace-stat-unit">{unit}</span>}
      </div>
      <div className="trace-stat-title">{title}</div>
    </div>
  );
}

// ===== 主组件 =====

export function TraceListPanel({ onOpenTrace }: TraceListPanelProps) {
  const requestRef = useRef(0);
  const [filterInput, setFilterInput] = useState("");
  const [activeFilter, setActiveFilter] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [pageData, setPageData] = useState<PageResult<RagTraceRunVO> | null>(null);
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
      const data = await apiGet<PageResult<RagTraceRunVO>>(`/api/rag/traces/runs?${params}`);
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
  const avgDuration = durations.length ? Math.round(durations.reduce((s, v) => s + v, 0) / durations.length) : 0;
  const p95Duration = Math.round(percentile(durations, 0.95));
  const successRate = runs.length ? Math.round((successCount / runs.length) * 1000) / 10 : 0;

  const currentPage = pageData?.current ?? pageNo;
  const totalPages = pageData?.pages ?? 1;
  const total = pageData?.total ?? 0;

  return (
    <div className="admin-page trace-list-page">
      {/* 页头 */}
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">链路追踪</h1>
          <p className="admin-page-desc">点击任意运行记录进入详情页分析慢节点与失败节点</p>
        </div>
        <div className="trace-list-toolbar">
          <input
            className="admin-input trace-filter-input"
            value={filterInput}
            onChange={(e) => setFilterInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") handleSearch(); }}
            placeholder="搜索 Trace Id..."
          />
          <button className="admin-btn admin-btn-primary admin-btn-sm" onClick={handleSearch} disabled={loading}>
            <SearchIcon /> 查询
          </button>
          <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={handleRefresh} disabled={loading}>
            <RefreshIcon /> 刷新
          </button>
        </div>
      </div>

      {error && <div className="admin-alert admin-alert-error">{error}</div>}

      {/* 统计卡片 */}
      <div className="trace-stat-grid">
        <StatCard
          color="cyan"
          title="成功率"
          value={`${successRate}%`}
        />
        <StatCard
          color="indigo"
          title="平均耗时"
          value={avgDuration < 1000 ? String(Math.round(avgDuration)) : (avgDuration / 1000).toFixed(2)}
          unit={avgDuration < 1000 ? "ms" : "s"}
        />
        <StatCard
          color="amber"
          title="P95 耗时"
          value={p95Duration < 1000 ? String(Math.round(p95Duration)) : (p95Duration / 1000).toFixed(2)}
          unit={p95Duration < 1000 ? "ms" : "s"}
        />
      </div>

      {/* 运行列表表格 */}
      <div className="admin-card">
        <div className="admin-card-header">
          <div>
            <h3 className="admin-card-title">运行列表</h3>
            <p className="admin-card-desc">按时间倒序查看运行记录，点击查看链路进入详情页</p>
          </div>
        </div>
        <div className="admin-card-body" style={{ padding: 0 }}>
          {loading && runs.length === 0 ? (
            <div className="admin-empty" style={{ padding: "48px 24px" }}>
              <p>加载中...</p>
            </div>
          ) : !loading && runs.length === 0 ? (
            <div className="admin-empty" style={{ padding: "48px 24px" }}>
              <p>暂无链路数据</p>
            </div>
          ) : (
            <div className="trace-table-wrap">
              <table className="trace-table">
                <thead>
                  <tr>
                    <th>Trace Name</th>
                    <th>Trace Id</th>
                    <th>会话ID / TaskID</th>
                    <th>用户名</th>
                    <th>耗时</th>
                    <th>状态</th>
                    <th>执行时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.map((run) => (
                    <tr key={run.traceId} className="trace-table-row">
                      <td>
                        <span className="trace-run-name" title={run.traceName ?? "-"}>
                          {run.traceName || "-"}
                        </span>
                      </td>
                      <td>
                        <span className="trace-run-id" title={run.traceId}>
                          {run.traceId.length > 16 ? `${run.traceId.slice(0, 8)}...${run.traceId.slice(-6)}` : run.traceId}
                        </span>
                      </td>
                      <td>
                        <p className="trace-meta-line" title={`会话ID: ${run.conversationId ?? "-"}`}>
                          {run.conversationId || "-"}
                        </p>
                        <p className="trace-meta-line trace-meta-secondary" title={`TaskID: ${run.taskId ?? "-"}`}>
                          {run.taskId || "-"}
                        </p>
                      </td>
                      <td>
                        <span className="trace-user-name">
                          {run.username || run.userId || "-"}
                        </span>
                      </td>
                      <td className="trace-duration-cell">
                        {formatDuration(run.durationMs)}
                      </td>
                      <td>
                        <span className={statusClass(run.status)}>
                          {statusLabel(run.status)}
                        </span>
                      </td>
                      <td className="trace-datetime-cell">
                        {formatDateTime(run.startTime)}
                      </td>
                      <td>
                        <button
                          className="admin-btn admin-btn-ghost admin-btn-sm trace-action-btn"
                          onClick={() => onOpenTrace(run.traceId)}
                        >
                          <EyeIcon /> 查看链路
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="admin-pagination" style={{ padding: "14px 20px" }}>
            <span className="admin-pagination-info">
              第 {currentPage} / {totalPages} 页，共 {total.toLocaleString("zh-CN")} 条
            </span>
            <div style={{ display: "flex", gap: 8 }}>
              <button
                className="admin-pagination-btn"
                disabled={currentPage <= 1 || loading}
                onClick={() => setPageNo((p) => Math.max(1, p - 1))}
              >
                上一页
              </button>
              <button
                className="admin-pagination-btn"
                disabled={currentPage >= totalPages || loading}
                onClick={() => setPageNo((p) => p + 1)}
              >
                下一页
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
