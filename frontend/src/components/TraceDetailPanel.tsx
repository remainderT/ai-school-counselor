import { useEffect, useMemo, useRef, useState } from "react";
import { apiGet, toErrorMessage } from "../lib/api";
import { pushToast } from "../lib/toast";

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

interface RagTraceNodeVO {
  nodeId: string;
  traceId: string;
  parentNodeId?: string | null;
  nodeName?: string | null;
  nodeType?: string | null;
  methodName?: string | null;
  depth?: number | null;
  status?: string | null;
  durationMs?: number | null;
  startTime?: string | null;
  endTime?: string | null;
  errorMessage?: string | null;
}

interface RagTraceDetail {
  run: RagTraceRunVO;
  nodes: RagTraceNodeVO[];
}

interface TraceDetailPanelProps {
  traceId: string;
  onBack: () => void;
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

function statusBadgeClass(status?: string | null): string {
  const s = normalizeStatus(status);
  if (s === "success") return "trace-badge trace-badge-success";
  if (s === "failed") return "trace-badge trace-badge-failed";
  if (s === "running") return "trace-badge trace-badge-running";
  return "trace-badge trace-badge-default";
}

function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(Number(ms)) || Number(ms) < 0) return "-";
  const v = Number(ms);
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60_000) return `${(v / 1000).toFixed(2)} s`;
  return `${(v / 1000).toFixed(1)} s`;
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

function toTimestamp(iso?: string | null): number {
  if (!iso) return 0;
  try {
    const ts = new Date(iso).getTime();
    return Number.isFinite(ts) ? ts : 0;
  } catch {
    return 0;
  }
}

function resolveNodeDuration(node: RagTraceNodeVO): number {
  if (node.durationMs != null && Number.isFinite(Number(node.durationMs))) {
    return Math.max(0, Number(node.durationMs));
  }
  const start = toTimestamp(node.startTime);
  const end = toTimestamp(node.endTime);
  return start > 0 && end > start ? end - start : 0;
}

function clamp(val: number, min: number, max: number): number {
  return Math.min(Math.max(val, min), max);
}

// ===== Inline SVG Icons =====

const BackIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 12H5" />
    <polyline points="12 19 5 12 12 5" />
  </svg>
);

const RefreshIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="23 4 23 10 17 10" />
    <polyline points="1 20 1 14 7 14" />
    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
  </svg>
);

const CopyIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
);

const ZapIcon = () => (
  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
  </svg>
);

// ===== 时间刻度 =====

function TimeScale({ totalMs }: { totalMs: number }) {
  const ticks = [0, 25, 50, 75, 100];
  return (
    <div className="trace-timescale">
      {ticks.map((p) => (
        <div key={p} className="trace-timescale-tick" style={{ left: `${p}%` }}>
          <div className="trace-timescale-line" />
          <span className="trace-timescale-label">{formatDuration((totalMs * p) / 100)}</span>
        </div>
      ))}
    </div>
  );
}

// ===== 瀑布图行 =====

interface WaterfallRowProps {
  node: RagTraceNodeVO & {
    depthValue: number;
    resolvedDurationMs: number;
    offsetMs: number;
    leftPercent: number;
    widthPercent: number;
  };
  isTopSlowest: boolean;
}

function WaterfallRow({ node, isTopSlowest }: WaterfallRowProps) {
  const status = normalizeStatus(node.status);
  const dotClass = status === "success" ? "trace-wf-dot success"
    : status === "failed" ? "trace-wf-dot failed"
    : status === "running" ? "trace-wf-dot running"
    : "trace-wf-dot default";
  const barClass = status === "success" ? "trace-wf-bar success"
    : status === "failed" ? "trace-wf-bar failed"
    : status === "running" ? "trace-wf-bar running"
    : "trace-wf-bar default";

  const displayName = node.nodeName || node.methodName || node.nodeId;

  return (
    <div className={`trace-wf-row${isTopSlowest ? " trace-wf-row-slowest" : ""}`}>
      {/* 节点名 */}
      <div
        className="trace-wf-col-name"
        style={{ paddingLeft: `${Math.min(node.depthValue, 6) * 16 + 4}px` }}
      >
        <span className={dotClass} />
        <span className="trace-wf-name-text" title={displayName}>{displayName}</span>
        {isTopSlowest && <span className="trace-wf-slowest-icon"><ZapIcon /></span>}
      </div>

      {/* 类型 */}
      <div className="trace-wf-col-type">
        <span className="trace-node-type-badge" title={node.nodeType ?? "-"}>
          {node.nodeType || "-"}
        </span>
      </div>

      {/* 时间条 */}
      <div className="trace-wf-col-bar">
        <div className="trace-wf-bar-track">
          {[25, 50, 75].map((p) => (
            <div key={p} className="trace-wf-bar-guide" style={{ left: `${p}%` }} />
          ))}
          <div
            className={barClass}
            style={{
              left: `${node.leftPercent}%`,
              width: `${Math.max(node.widthPercent, 0.5)}%`,
              minWidth: 4
            }}
            title={`${displayName} — ${formatDuration(node.resolvedDurationMs)}`}
          />
        </div>
      </div>

      {/* 耗时 */}
      <div className="trace-wf-col-duration">
        <span className="trace-wf-duration">{formatDuration(node.resolvedDurationMs)}</span>
        <span className="trace-wf-offset">@{formatDuration(node.offsetMs)}</span>
      </div>
    </div>
  );
}

// ===== 主组件 =====

export function TraceDetailPanel({ traceId, onBack }: TraceDetailPanelProps) {
  const requestRef = useRef(0);
  const [detail, setDetail] = useState<RagTraceDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadDetail = async (id: string) => {
    if (!id) return;
    const reqId = ++requestRef.current;
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet<RagTraceDetail>(`/api/rag/traces/runs/${encodeURIComponent(id)}/nodes`);
      if (requestRef.current !== reqId) return;
      setDetail(data);
    } catch (e) {
      if (requestRef.current !== reqId) return;
      setError(toErrorMessage(e, "加载链路详情失败"));
    } finally {
      if (requestRef.current !== reqId) return;
      setLoading(false);
    }
  };

  useEffect(() => {
    setDetail(null);
    void loadDetail(traceId);
  }, [traceId]);

  const run = detail?.run ?? null;

  // 计算瀑布图布局
  const timeline = useMemo(() => {
    const nodes = detail?.nodes ?? [];
    if (!nodes.length) return { totalWindowMs: 0, rows: [] as any[] };

    const normalized = nodes.map((node) => {
      const startTs = toTimestamp(node.startTime);
      const endTs = toTimestamp(node.endTime);
      const resolvedDurationMs = resolveNodeDuration(node);
      const depthValue = Math.max(0, Number(node.depth ?? 0));
      const resolvedEndTs = endTs > 0 ? endTs : (startTs > 0 ? startTs + resolvedDurationMs : 0);
      return { ...node, depthValue, resolvedDurationMs, startTs, endTs: resolvedEndTs };
    });

    const withTime = normalized.filter((n) => n.startTs > 0);
    const baseStart = withTime.length
      ? withTime.reduce((min, n) => Math.min(min, n.startTs), withTime[0].startTs)
      : Date.now();
    const maxEnd = withTime.length
      ? withTime.reduce((max, n) => Math.max(max, n.endTs || n.startTs), withTime[0].endTs || withTime[0].startTs)
      : baseStart;
    const runDurationMs = Number(run?.durationMs ?? 0);
    const windowDuration = Math.max(runDurationMs > 0 ? runDurationMs : maxEnd - baseStart, 1);

    const rows = [...normalized]
      .sort((a, b) => a.startTs - b.startTs || a.depthValue - b.depthValue)
      .map((node) => {
        const offsetMs = node.startTs > 0 ? Math.max(0, node.startTs - baseStart) : 0;
        const leftPercent = clamp((offsetMs / windowDuration) * 100, 0, 99.2);
        const widthPercent = clamp(
          (Math.max(node.resolvedDurationMs, 1) / windowDuration) * 100,
          0.8,
          100 - leftPercent
        );
        return { ...node, offsetMs, leftPercent, widthPercent };
      });

    return { totalWindowMs: windowDuration, rows };
  }, [detail?.nodes, run?.durationMs]);

  // 节点统计
  const nodeStats = useMemo(() => {
    const nodes = detail?.nodes ?? [];
    const total = nodes.length;
    const failed = nodes.filter((n) => normalizeStatus(n.status) === "failed").length;
    const success = nodes.filter((n) => normalizeStatus(n.status) === "success").length;
    const running = nodes.filter((n) => normalizeStatus(n.status) === "running").length;
    const durations = nodes.map((n) => resolveNodeDuration(n));
    const avgDuration = total > 0 ? Math.round(durations.reduce((a, b) => a + b, 0) / total) : 0;
    const sortedByDuration = [...nodes].sort((a, b) => resolveNodeDuration(b) - resolveNodeDuration(a));
    const topSlowestId = sortedByDuration[0]?.nodeId ?? null;
    return { total, failed, success, running, avgDuration, topSlowestId };
  }, [detail?.nodes]);

  const handleCopyTraceId = () => {
    navigator.clipboard.writeText(traceId).then(
      () => pushToast("Trace Id 已复制", "success"),
      () => pushToast("复制失败", "error")
    );
  };

  const shortTraceId = traceId.length > 28
    ? `${traceId.slice(0, 12)}...${traceId.slice(-8)}`
    : traceId;

  if (loading && !detail) {
    return (
      <div className="admin-page">
        <div className="admin-empty" style={{ padding: "80px 24px" }}>
          <p>加载链路详情中...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="admin-page trace-detail-page">
      {/* 页头 */}
      <div className="admin-page-header">
        <div>
          <button type="button" className="admin-btn admin-btn-ghost admin-btn-sm doc-detail-back" onClick={onBack}>
            <BackIcon /> 返回链路列表
          </button>
          <div className="trace-detail-title-row">
            <h1 className="admin-page-title">{run?.traceName || "未命名链路"}</h1>
            {run?.status && (
              <span className={statusBadgeClass(run.status)}>
                {statusLabel(run.status)}
              </span>
            )}
          </div>
        </div>
        <button
          className="admin-btn admin-btn-ghost admin-btn-sm"
          onClick={() => void loadDetail(traceId)}
          disabled={loading}
        >
          <RefreshIcon /> 刷新
        </button>
      </div>

      {error && <div className="admin-alert admin-alert-error">{error}</div>}

      {/* 元信息 */}
      {run && (
        <div className="trace-detail-meta-row">
          <button
            className="trace-meta-copy-btn"
            onClick={handleCopyTraceId}
            title="点击复制 Trace Id"
          >
            <CopyIcon /> {shortTraceId}
          </button>
          <span className="trace-meta-item">{formatDateTime(run.startTime)}</span>
          {(run.username || run.userId) && (
            <span className="trace-meta-item">{run.username || run.userId}</span>
          )}
        </div>
      )}

      {/* 错误提示 */}
      {run?.errorMessage && (
        <div className="admin-alert admin-alert-error">
          <strong>执行出错：</strong>{run.errorMessage}
        </div>
      )}

      {/* 指标条 */}
      {run && (
        <div className="trace-metrics-bar">
          <div className="trace-metric-item">
            <span className="trace-metric-value trace-metric-primary">{formatDuration(run.durationMs)}</span>
            <span className="trace-metric-label">总耗时</span>
          </div>
          <div className="trace-metric-sep" />
          <div className="trace-metric-item">
            <span className="trace-metric-value">{nodeStats.total}</span>
            <span className="trace-metric-label">节点</span>
          </div>
          <div className="trace-metric-sep" />
          <div className="trace-metric-item">
            <span className="trace-metric-value trace-metric-success">{nodeStats.success}</span>
            <span className="trace-metric-label">成功</span>
          </div>
          <div className="trace-metric-sep" />
          <div className="trace-metric-item">
            <span className={`trace-metric-value${nodeStats.failed > 0 ? " trace-metric-error" : ""}`}>{nodeStats.failed}</span>
            <span className="trace-metric-label">失败</span>
          </div>
          {nodeStats.running > 0 && (
            <>
              <div className="trace-metric-sep" />
              <div className="trace-metric-item">
                <span className="trace-metric-value trace-metric-warning">{nodeStats.running}</span>
                <span className="trace-metric-label">运行中</span>
              </div>
            </>
          )}
          <div className="trace-metric-sep" />
          <div className="trace-metric-item">
            <span className="trace-metric-value">{formatDuration(nodeStats.avgDuration)}</span>
            <span className="trace-metric-label">平均耗时</span>
          </div>
        </div>
      )}

      {/* 瀑布图 */}
      <div className="admin-card">
        <div className="admin-card-header">
          <div>
            <h3 className="admin-card-title">执行时序</h3>
          </div>
          <span className="admin-badge">窗口 {formatDuration(timeline.totalWindowMs)}</span>
        </div>
        <div className="admin-card-body" style={{ padding: 0 }}>
          {timeline.rows.length === 0 ? (
            <div className="admin-empty" style={{ padding: "48px 24px" }}>
              <p>暂无节点记录</p>
            </div>
          ) : (
            <div>
              {/* 表头 */}
              <div className="trace-wf-header">
                <div className="trace-wf-col-name">节点</div>
                <div className="trace-wf-col-type">类型</div>
                <div className="trace-wf-col-bar">
                  <TimeScale totalMs={timeline.totalWindowMs} />
                </div>
                <div className="trace-wf-col-duration">耗时</div>
              </div>

              {/* 行 */}
              <div className="trace-wf-body">
                {timeline.rows.map((node) => (
                  <WaterfallRow
                    key={node.nodeId}
                    node={node}
                    isTopSlowest={node.nodeId === nodeStats.topSlowestId}
                  />
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
