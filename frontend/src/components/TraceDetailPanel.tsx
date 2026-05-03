import { useEffect, useMemo, useRef, useState } from "react";
import { apiGet, toErrorMessage } from "../lib/api";
import { pushToast } from "../lib/toast";

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

interface TraceStepVO {
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

interface TraceDetailData {
  run: TraceRecordVO;
  nodes: TraceStepVO[];
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
  if (s === "success") return "SUCCESS";
  if (s === "failed") return "FAILED";
  if (s === "running") return "RUNNING";
  return status || "-";
}

function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(Number(ms)) || Number(ms) < 0) return "-";
  const v = Number(ms);
  if (v < 1000) return `${Math.round(v)}ms`;
  if (v < 60_000) return `${(v / 1000).toFixed(2)}s`;
  return `${(v / 1000).toFixed(1)}s`;
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

function resolveNodeDuration(node: TraceStepVO): number {
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

/** 节点类型 → 颜色标签映射 */
const NODE_TYPE_COLORS: Record<string, { bg: string; text: string }> = {
  REWRITE:             { bg: "#ede9fe", text: "#7c3aed" },
  INTENT:              { bg: "#e0f2fe", text: "#0284c7" },
  RETRIEVE_CHANNEL:    { bg: "#ecfdf5", text: "#059669" },
  CRAG_EVAL:           { bg: "#fef3c7", text: "#b45309" },
  SUB_QUERY_RETRIEVAL: { bg: "#fce7f3", text: "#be185d" },
  ROUTE_EXECUTE:       { bg: "#f3e8ff", text: "#9333ea" },
  LLM_CHAT:            { bg: "#dbeafe", text: "#2563eb" },
  METHOD:              { bg: "#f1f5f9", text: "#475569" },
};

function getNodeColor(type?: string | null) {
  if (!type) return { bg: "#f1f5f9", text: "#64748b" };
  return NODE_TYPE_COLORS[type] ?? { bg: "#f1f5f9", text: "#64748b" };
}

// ===== Inline SVG Icons =====

const ArrowLeftIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 12H5" /><polyline points="12 19 5 12 12 5" />
  </svg>
);

const RefreshIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="23 4 23 10 17 10" /><polyline points="1 20 1 14 7 14" />
    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
  </svg>
);

const CopyIcon = () => (
  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
);

const ClockIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
  </svg>
);

const CalendarIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/>
  </svg>
);

const UserIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
  </svg>
);

const ListIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>
  </svg>
);

const ZapIcon = () => (
  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
  </svg>
);

// ===== 主组件 =====

export function TraceDetailPanel({ traceId, onBack }: TraceDetailPanelProps) {
  const requestRef = useRef(0);
  const [detail, setDetail] = useState<TraceDetailData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadDetail = async (id: string) => {
    if (!id) return;
    const reqId = ++requestRef.current;
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet<TraceDetailData>(`/api/rag/traces/runs/${encodeURIComponent(id)}/nodes`);
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
    const durations = nodes.map((n) => resolveNodeDuration(n));
    const avgDuration = total > 0 ? Math.round(durations.reduce((a, b) => a + b, 0) / total) : 0;
    const sortedByDuration = [...nodes].sort((a, b) => resolveNodeDuration(b) - resolveNodeDuration(a));
    const topSlowestId = sortedByDuration[0]?.nodeId ?? null;
    return { total, failed, success, avgDuration, topSlowestId };
  }, [detail?.nodes]);

  const handleCopyTraceId = () => {
    navigator.clipboard.writeText(traceId).then(
      () => pushToast("Trace ID 已复制", "success"),
      () => pushToast("复制失败", "error")
    );
  };

  if (loading && !detail) {
    return (
      <div className="admin-page">
        <div className="admin-empty" style={{ padding: "80px 24px" }}><p>加载链路详情中...</p></div>
      </div>
    );
  }

  const runStatus = normalizeStatus(run?.status);
  const statusColorMap = { success: "#10b981", failed: "#ef4444", running: "#f59e0b" };
  const statusColor = runStatus ? statusColorMap[runStatus] : "#94a3b8";
  const statusCls = runStatus === "success" ? "trc-tag-success" : runStatus === "failed" ? "trc-tag-failed" : runStatus === "running" ? "trc-tag-running" : "trc-tag-default";

  return (
    <div className="admin-page trc-detail-page">
      {/* 顶部导航 */}
      <div className="trc-detail-nav">
        <button className="trc-back-link" onClick={onBack}>
          <ArrowLeftIcon />
          返回列表
        </button>
        <div className="trc-detail-actions">
          <button className="trc-refresh-btn" onClick={() => void loadDetail(traceId)} disabled={loading} title="刷新">
            <RefreshIcon />
          </button>
        </div>
      </div>

      {error && <div className="admin-alert admin-alert-error">{error}</div>}

      {/* 错误信息 banner */}
      {run?.errorMessage && (
        <div className="trc-error-banner">
          <strong>执行异常：</strong>{run.errorMessage}
        </div>
      )}

      {/* 左右分栏主体 */}
      <div className="trc-detail-split">
        {/* ===== 左侧：概览面板 ===== */}
        {run && (
          <div className="trc-overview-panel">
            <div className="trc-overview-header">
              <div className="trc-overview-title-row">
                <span className="trc-overview-dot" style={{ background: statusColor }} />
                <h2 className="trc-overview-name">{run.traceName || "stream-chat"}</h2>
              </div>
              <span className={`trc-overview-status-tag ${statusCls}`} style={{ background: `${statusColor}18`, color: statusColor, border: `1px solid ${statusColor}40` }}>
                {statusLabel(run.status)}
              </span>
              <button className="trc-overview-trace-id" onClick={handleCopyTraceId} title="点击复制 Trace ID">
                <CopyIcon />
                {traceId.length > 18 ? `${traceId.slice(0, 8)}...${traceId.slice(-6)}` : traceId}
              </button>
            </div>

            <div className="trc-overview-info">
              <div className="trc-info-row">
                <span className="trc-info-label"><ClockIcon /> 总耗时</span>
                <span className="trc-info-value">{formatDuration(run.durationMs)}</span>
              </div>
              <div className="trc-info-row">
                <span className="trc-info-label"><CalendarIcon /> 开始时间</span>
                <span className="trc-info-value">{formatDateTime(run.startTime)}</span>
              </div>
              <div className="trc-info-row">
                <span className="trc-info-label"><CalendarIcon /> 结束时间</span>
                <span className="trc-info-value">{formatDateTime(run.endTime)}</span>
              </div>
              {(run.username || run.userId) && (
                <div className="trc-info-row">
                  <span className="trc-info-label"><UserIcon /> 用户</span>
                  <span className="trc-info-value">{run.username || run.userId}</span>
                </div>
              )}
            </div>

            {/* 底部 2x2 统计网格 */}
            <div className="trc-overview-stats">
              <div className="trc-stat-cell">
                <span className="trc-stat-cell-val">{nodeStats.total}</span>
                <span className="trc-stat-cell-label">执行步骤</span>
              </div>
              <div className="trc-stat-cell">
                <span className="trc-stat-cell-val trc-val-success">{nodeStats.success}</span>
                <span className="trc-stat-cell-label">成功</span>
              </div>
              <div className="trc-stat-cell">
                <span className={`trc-stat-cell-val${nodeStats.failed > 0 ? " trc-val-error" : ""}`}>{nodeStats.failed}</span>
                <span className="trc-stat-cell-label">失败</span>
              </div>
              <div className="trc-stat-cell">
                <span className="trc-stat-cell-val">{formatDuration(nodeStats.avgDuration)}</span>
                <span className="trc-stat-cell-label">平均耗时</span>
              </div>
            </div>
          </div>
        )}

        {/* ===== 右侧：瀑布图时间线 ===== */}
        <div className="trc-waterfall-panel">
          <div className="trc-waterfall-header">
            <h3 className="trc-waterfall-title">
              <ListIcon />
              执行时序
            </h3>
            <span className="trc-waterfall-window">时间窗口 {formatDuration(timeline.totalWindowMs)}</span>
          </div>

          <div>
            {timeline.rows.length === 0 ? (
              <div className="admin-empty" style={{ padding: "48px 24px" }}><p>暂无步骤记录</p></div>
            ) : (
              <>
                {/* 表头 */}
                <div className="trc-wf-header">
                  <div>步骤节点</div>
                  <div>类型</div>
                  <div>
                    <div className="trc-wf-timescale">
                      {[0, 25, 50, 75, 100].map((p) => (
                        <span key={p} className="trc-wf-tick" style={{ left: `${p}%` }}>
                          {formatDuration((timeline.totalWindowMs * p) / 100)}
                        </span>
                      ))}
                    </div>
                  </div>
                  <div style={{ textAlign: "right" }}>耗时</div>
                </div>

                {/* 行 */}
                <div className="trc-wf-rows">
                  {timeline.rows.map((node: any) => {
                    const status = normalizeStatus(node.status);
                    const isSlowest = node.nodeId === nodeStats.topSlowestId;
                    const color = getNodeColor(node.nodeType);
                    const barColor = status === "failed" ? "#ef4444" : status === "running" ? "#f59e0b" : "#10b981";

                    return (
                      <div key={node.nodeId} className={`trc-wf-row${isSlowest ? " trc-wf-row-slowest" : ""}`}>
                        <div className="trc-wf-col-name" style={{ paddingLeft: `${Math.min(node.depthValue, 5) * 18 + 8}px` }}>
                          <span className={`trc-wf-dot ${status === "success" ? "success" : status === "failed" ? "failed" : "running"}`} />
                          <span className="trc-wf-name" title={node.nodeName || node.methodName || node.nodeId}>
                            {node.nodeName || node.methodName || node.nodeId}
                          </span>
                          {isSlowest && (
                            <span className="trc-wf-slowest-tag">
                              <ZapIcon />
                              最慢
                            </span>
                          )}
                        </div>
                        <div className="trc-wf-col-type">
                          <span className="trc-wf-type-badge" style={{ background: color.bg, color: color.text }}>
                            {node.nodeType || "METHOD"}
                          </span>
                        </div>
                        <div className="trc-wf-col-bar">
                          <div className="trc-wf-bar-track">
                            {[25, 50, 75].map((p) => (
                              <div key={p} className="trc-wf-bar-guide" style={{ left: `${p}%` }} />
                            ))}
                            <div
                              className="trc-wf-bar"
                              style={{
                                left: `${node.leftPercent}%`,
                                width: `${Math.max(node.widthPercent, 0.5)}%`,
                                minWidth: 4,
                                background: `linear-gradient(90deg, ${barColor}, ${barColor}cc)`,
                              }}
                              title={`${node.nodeName} — ${formatDuration(node.resolvedDurationMs)}`}
                            />
                          </div>
                        </div>
                        <div className="trc-wf-col-duration">
                          <span className="trc-wf-dur">{formatDuration(node.resolvedDurationMs)}</span>
                          <span className="trc-wf-offset">@{formatDuration(node.offsetMs)}</span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
