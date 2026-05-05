import { useEffect, useMemo, useState } from "react";
import { apiDelete, apiGet, apiPost, apiPut } from "../lib/api";
import type { IntentNodeItem } from "../types";
import { useActionRequest } from "../hooks/useActionRequest";
import { CustomSelect } from "./CustomSelect";

type FormState = {
  nodeId: string;
  nodeName: string;
  parentId: string;
  nodeType: string;
  description: string;
  promptSnippet: string;
  keywords: string;
  knowledgeBaseId: string;
  actionService: string;
  mcpToolId: string;
  topK: string;
  enabled: string;
};

const defaultForm: FormState = {
  nodeId: "",
  nodeName: "",
  parentId: "root",
  nodeType: "GROUP",
  description: "",
  promptSnippet: "",
  keywords: "",
  knowledgeBaseId: "",
  actionService: "",
  mcpToolId: "",
  topK: "",
  enabled: "1"
};

function flatten(nodes: IntentNodeItem[]): IntentNodeItem[] {
  const all: IntentNodeItem[] = [];
  const stack = [...nodes].reverse();
  while (stack.length > 0) {
    const item = stack.pop();
    if (!item) continue;
    all.push(item);
    const children = item.children || [];
    for (let i = children.length - 1; i >= 0; i -= 1) {
      stack.push(children[i]);
    }
  }
  return all;
}

function toForm(item?: IntentNodeItem): FormState {
  if (!item) return defaultForm;
  return {
    nodeId: item.nodeId || "",
    nodeName: item.nodeName || "",
    parentId: item.parentId || "root",
    nodeType: item.nodeType || "GROUP",
    description: item.description || "",
    promptSnippet: item.promptSnippet || "",
    keywords: (item.keywords || []).join(","),
    knowledgeBaseId: item.knowledgeBaseId == null ? "" : String(item.knowledgeBaseId),
    actionService: item.actionService || "",
    mcpToolId: item.mcpToolId || "",
    topK: item.topK == null ? "" : String(item.topK),
    enabled: item.enabled == null ? "1" : String(item.enabled)
  };
}

function buildPayload(form: FormState, withNodeId: boolean) {
  const payload: Record<string, unknown> = {
    nodeName: form.nodeName.trim(),
    parentId: form.parentId.trim() || "root",
    nodeType: form.nodeType,
    description: form.description.trim() || undefined,
    promptSnippet: form.promptSnippet.trim() || undefined,
    keywords: form.keywords
      .split(",")
      .map((it) => it.trim())
      .filter(Boolean),
    knowledgeBaseId: form.knowledgeBaseId ? Number(form.knowledgeBaseId) : undefined,
    actionService: form.actionService ? form.actionService.trim() || undefined : undefined,
    mcpToolId: form.mcpToolId ? form.mcpToolId.trim() || undefined : undefined,
    topK: form.topK ? Number(form.topK) : undefined,
    enabled: Number(form.enabled || "1")
  };
  if (withNodeId) {
    payload.nodeId = form.nodeId.trim();
  }
  return payload;
}

function nodeMatchesKeyword(item: IntentNodeItem, keyword: string): boolean {
  const kw = keyword.trim().toLowerCase();
  if (!kw) return true;
  const bag = [
    item.nodeId || "",
    item.nodeName || "",
    item.description || "",
    item.nodeType || "",
    ...(item.keywords || [])
  ]
    .join(" ")
    .toLowerCase();
  return bag.includes(kw);
}

function filterTree(nodes: IntentNodeItem[], keyword: string): IntentNodeItem[] {
  const kw = keyword.trim();
  if (!kw) return nodes;
  const result: IntentNodeItem[] = [];
  for (const item of nodes) {
    const children = filterTree(item.children || [], kw);
    if (nodeMatchesKeyword(item, kw) || children.length > 0) {
      result.push({ ...item, children });
    }
  }
  return result;
}

function collectParentIds(nodes: IntentNodeItem[]): string[] {
  const ids: string[] = [];
  const stack = [...nodes];
  while (stack.length > 0) {
    const node = stack.pop();
    if (!node) continue;
    if ((node.children || []).length > 0) {
      ids.push(node.nodeId);
      stack.push(...(node.children || []));
    }
  }
  return ids;
}

/* ===== Icons ===== */
const ChevronRight = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="9 18 15 12 9 6" />
  </svg>
);
const ChevronDown = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

export function IntentTreePanel() {
  const [tree, setTree] = useState<IntentNodeItem[]>([]);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<FormState>(defaultForm);
  const [msg, setMsg] = useState("");
  const [keyword, setKeyword] = useState("");
  const [collapsedNodeIds, setCollapsedNodeIds] = useState<Set<string>>(new Set());
  const listReq = useActionRequest();
  const actionReq = useActionRequest();

  const flatNodes = useMemo(() => flatten(tree), [tree]);
  const activeNode = useMemo(
    () => (activeId == null ? undefined : flatNodes.find((it) => it.id === activeId)),
    [flatNodes, activeId]
  );
  const filteredTree = useMemo(() => filterTree(tree, keyword), [tree, keyword]);

  const loadTree = async () => {
    const result = await listReq.runAction(() => apiGet<IntentNodeItem[]>("/api/rag/intent-tree/trees"), {
      errorFallback: "加载意图树失败",
      onError: setMsg
    });
    if (result.ok) {
      setTree(result.data || []);
    }
  };

  useEffect(() => {
    void loadTree();
  }, []);

  useEffect(() => {
    if (creating) {
      setForm(defaultForm);
      return;
    }
    setForm(toForm(activeNode));
  }, [activeNode, creating]);

  const toggle = (id: number) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((it) => it !== id) : [...prev, id]));
  };

  const submitCreate = async () => {
    if (!form.nodeId.trim() || !form.nodeName.trim()) {
      setMsg("创建节点需填写 nodeId 和 nodeName");
      return;
    }
    const result = await actionReq.runAction(() => apiPost<number>("/api/rag/intent-tree", buildPayload(form, true)), {
      successToast: "创建节点成功",
      errorFallback: "创建失败",
      onError: setMsg
    });
    if (result.ok) {
      setMsg("");
      setCreating(false);
      await loadTree();
    }
  };

  const submitUpdate = async () => {
    if (!activeNode) {
      setMsg("请先选择要编辑的节点");
      return;
    }
    if (!form.nodeName.trim()) {
      setMsg("nodeName 不能为空");
      return;
    }
    const result = await actionReq.runAction(
      () => apiPut<void>(`/api/rag/intent-tree/${activeNode.id}`, buildPayload(form, false)),
      {
        successToast: "更新节点成功",
        errorFallback: "更新失败",
        onError: setMsg
      }
    );
    if (result.ok) {
      setMsg("");
      await loadTree();
    }
  };

  const deleteOne = async () => {
    if (!activeNode) {
      setMsg("请先选择要删除的节点");
      return;
    }
    const result = await actionReq.runAction(() => apiDelete<void>(`/api/rag/intent-tree/${activeNode.id}`), {
      successToast: "删除节点成功",
      errorFallback: "删除失败",
      onError: setMsg
    });
    if (result.ok) {
      setMsg("");
      setActiveId(null);
      setSelectedIds((prev) => prev.filter((id) => id !== activeNode.id));
      await loadTree();
    }
  };

  const batchAction = async (path: string, successText: string) => {
    if (selectedIds.length === 0) {
      setMsg("请先勾选节点");
      return;
    }
    const result = await actionReq.runAction(() => apiPost(path, { ids: selectedIds }), {
      successToast: successText,
      errorFallback: "批量操作失败",
      onError: setMsg
    });
    if (result.ok) {
      setMsg("");
      setSelectedIds([]);
      if (activeId != null && !selectedIds.includes(activeId)) {
        setActiveId(null);
      }
      await loadTree();
    }
  };

  const toggleCollapse = (nodeId: string) => {
    setCollapsedNodeIds((prev) => {
      const next = new Set(prev);
      if (next.has(nodeId)) {
        next.delete(nodeId);
      } else {
        next.add(nodeId);
      }
      return next;
    });
  };

  const collapseAll = () => {
    setCollapsedNodeIds(new Set(collectParentIds(tree)));
  };

  const expandAll = () => {
    setCollapsedNodeIds(new Set());
  };

  const renderNode = (item: IntentNodeItem, depth: number) => {
    const checked = selectedIds.includes(item.id);
    const isActive = activeId === item.id;
    const hasChildren = (item.children || []).length > 0;
    const collapsed = keyword.trim() ? false : collapsedNodeIds.has(item.nodeId);
    return (
      <div key={item.id}>
        <div
          className={`intent-tree-node ${isActive ? "active" : ""}`}
          style={{ paddingLeft: 12 + depth * 20 }}
        >
          <input
            type="checkbox"
            className="intent-tree-checkbox"
            checked={checked}
            onChange={() => toggle(item.id)}
          />
          {hasChildren ? (
            <button className="intent-tree-toggle" onClick={() => toggleCollapse(item.nodeId)}>
              {collapsed ? <ChevronRight /> : <ChevronDown />}
            </button>
          ) : (
            <span style={{ width: 22, display: "inline-block" }} />
          )}
          <button
            className="intent-tree-link"
            onClick={() => { setActiveId(item.id); setCreating(false); }}
          >
            <span className="intent-tree-name">{item.nodeName}</span>
            <span className="intent-tree-id">{item.nodeId}</span>
          </button>
          <span className="admin-tag admin-tag-sm">{item.nodeType || "-"}</span>
          <span className={`admin-status-dot ${item.enabled === 1 ? "admin-status-dot-success" : "admin-status-dot-error"}`} />
        </div>
        {!collapsed ? (item.children || []).map((child) => renderNode(child, depth + 1)) : null}
      </div>
    );
  };

  return (
    <div className="admin-page admin-page-shell">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">意图树管理</h1>
          <p className="admin-page-desc">维护意图节点层级、路由属性与批量运维操作</p>
        </div>
      </div>

      {/* Toolbar */}
      <div className="admin-card admin-toolbar-card intent-toolbar-card">
        <div className="admin-card-body">
          <div className="intent-toolbar">
            <div className="intent-toolbar-search">
              <SearchIcon />
              <input
                className="intent-toolbar-input"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="搜索意图名称/ID..."
              />
            </div>
            <div className="intent-toolbar-actions">
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={expandAll}>展开全部</button>
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={collapseAll}>折叠全部</button>
              <button className="admin-btn admin-btn-primary admin-btn-sm" onClick={() => { setCreating(true); setActiveId(null); }}>+ 新建节点</button>
            </div>
          </div>
        </div>
      </div>

      {/* Batch Actions */}
      {selectedIds.length > 0 && (
        <div className="admin-card admin-card-highlight">
          <div className="admin-card-body">
            <div className="intent-batch-bar">
              <span className="admin-badge">已选 {selectedIds.length} 项</span>
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={() => void batchAction("/api/rag/intent-tree/batch/enable", "批量启用成功")} disabled={actionReq.loading}>批量启用</button>
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={() => void batchAction("/api/rag/intent-tree/batch/disable", "批量停用成功")} disabled={actionReq.loading}>批量停用</button>
              <button className="admin-btn admin-btn-danger admin-btn-sm" onClick={() => void batchAction("/api/rag/intent-tree/batch/delete", "批量删除成功")} disabled={actionReq.loading}>批量删除</button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className={`admin-alert ${msg.includes("成功") || msg.includes("已") ? "admin-alert-success" : "admin-alert-error"}`}>{msg}</div>}
      {!msg && listReq.error && <div className="admin-alert admin-alert-error">{listReq.error}</div>}

      {/* Main Content: Tree + Detail */}
      <div className="intent-layout">
        {/* Tree Panel */}
        <div className="admin-card admin-list-card intent-tree-card">
          <div className="admin-card-header admin-card-header-rich">
            <div>
              <h3 className="admin-card-title">意图树结构</h3>
              <p className="admin-card-desc">点击节点查看详情或进行编辑</p>
            </div>
          </div>
          <div className="intent-tree-body">
            {filteredTree.length === 0 ? (
              <div className="admin-empty intent-inline-empty">
                <h3 className="admin-empty-title">暂无匹配节点</h3>
                <p className="admin-empty-desc">可以调整搜索关键字，或展开全部后重新查看树结构。</p>
              </div>
            ) : (
              filteredTree.map((item) => renderNode(item, 0))
            )}
          </div>
        </div>

        {/* Detail / Edit Panel */}
        <div className="admin-card admin-list-card intent-detail-card">
          <div className="admin-card-header admin-card-header-rich">
            <div>
              <h3 className="admin-card-title">{creating ? "创建节点" : activeNode ? "节点详情" : "节点详情"}</h3>
              <p className="admin-card-desc">{creating ? "填写信息创建新节点" : "查看并管理当前选择的节点"}</p>
            </div>
            {activeNode && !creating && <span className="admin-badge">ID: {activeNode.nodeId}</span>}
          </div>
          <div className="admin-card-body">
            {/* Node header info */}
            {!creating && activeNode && (
              <div className="intent-detail-header">
                <div className="intent-detail-name">
                  {activeNode.nodeName}
                  <span className="admin-tag">{activeNode.nodeType}</span>
                  <span className={`admin-tag ${activeNode.enabled === 1 ? "admin-tag-success" : "admin-tag-error"}`}>
                    {activeNode.enabled === 1 ? "启用" : "停用"}
                  </span>
                </div>
                <div className="intent-detail-id">{activeNode.nodeId}</div>
                <div className="intent-detail-actions">
                   <button className="admin-btn admin-btn-primary admin-btn-sm" onClick={() => { setCreating(true); setForm({ ...defaultForm, parentId: activeNode.nodeId }); }}>
                     + 新建子节点
                   </button>
                   <button className="admin-btn admin-btn-danger-ghost admin-btn-sm" onClick={() => void deleteOne()} disabled={actionReq.loading}>
                     删除节点
                   </button>
                 </div>
              </div>
            )}

            {/* Node info table (view mode) */}
            {!creating && activeNode && (
              <div className="intent-detail-info">
                <div className="intent-info-row"><span className="intent-info-label">父节点</span><span className="intent-info-value">{activeNode.parentId || "ROOT"}</span></div>
                <div className="intent-info-row"><span className="intent-info-label">知识库</span><span className="intent-info-value">{activeNode.knowledgeBaseId || "无"}</span></div>
                <div className="intent-info-row"><span className="intent-info-label">节点 TopK</span><span className="intent-info-value">{activeNode.topK ?? "默认（全局）"}</span></div>
                {activeNode.keywords && activeNode.keywords.length > 0 && (
                  <div className="intent-info-row">
                    <span className="intent-info-label">关键词</span>
                    <span className="intent-info-value">{activeNode.keywords.join(", ")}</span>
                  </div>
                )}
                <div className="intent-info-section">
                  <h4 className="intent-info-section-title">描述</h4>
                  <p className="admin-muted">{activeNode.description || "暂无描述"}</p>
                </div>
                {activeNode.promptSnippet && (
                  <div className="intent-info-section">
                    <h4 className="intent-info-section-title">Prompt Snippet</h4>
                    <pre className="intent-info-pre">{activeNode.promptSnippet}</pre>
                  </div>
                )}
              </div>
            )}

            {/* Edit / Create Form */}
            {(creating || !activeNode) && (
              <div className="intent-edit-form">
                {!creating && !activeNode && (
                  <div className="admin-empty intent-inline-empty">
                    <div className="admin-empty-icon">🌳</div>
                    <h3 className="admin-empty-title">请选择一个节点</h3>
                    <p className="admin-empty-desc">你可以先从左侧树中选择节点查看详情，或者直接点击“新建节点”开始创建。</p>
                  </div>
                )}
                {creating && (
                  <>
                    <div className="admin-form-grid admin-form-grid-2">
                      <div className="admin-form-group">
                        <label className="admin-label">nodeId *</label>
                        <input className="admin-input" value={form.nodeId} onChange={(e) => setForm((prev) => ({ ...prev, nodeId: e.target.value }))} placeholder="唯一标识" />
                      </div>
                      <div className="admin-form-group">
                        <label className="admin-label">nodeName *</label>
                        <input className="admin-input" value={form.nodeName} onChange={(e) => setForm((prev) => ({ ...prev, nodeName: e.target.value }))} placeholder="节点名称" />
                      </div>
                    </div>
                    <div className="admin-form-grid admin-form-grid-2">
                      <div className="admin-form-group">
                        <label className="admin-label">parentId</label>
                        <input className="admin-input" value={form.parentId} onChange={(e) => setForm((prev) => ({ ...prev, parentId: e.target.value }))} />
                      </div>
                      <div className="admin-form-group">
                        <label className="admin-label">nodeType</label>
                        <CustomSelect
                          value={form.nodeType}
                          options={[
                            { value: "GROUP", label: "GROUP" },
                            { value: "RAG_QA", label: "RAG_QA" },
                            { value: "API_ACTION", label: "API_ACTION" },
                            { value: "CHITCHAT", label: "CHITCHAT" }
                          ]}
                          onChange={(v) => setForm((prev) => ({ ...prev, nodeType: v }))}
                        />
                      </div>
                    </div>
                    <div className="admin-form-grid admin-form-grid-2">
                      <div className="admin-form-group">
                        <label className="admin-label">keywords（逗号分隔）</label>
                        <input className="admin-input" value={form.keywords} onChange={(e) => setForm((prev) => ({ ...prev, keywords: e.target.value }))} />
                      </div>
                      <div className="admin-form-group">
                        <label className="admin-label">enabled</label>
                        <CustomSelect
                          value={form.enabled}
                          options={[
                            { value: "1", label: "启用" },
                            { value: "0", label: "停用" }
                          ]}
                          onChange={(v) => setForm((prev) => ({ ...prev, enabled: v }))}
                        />
                      </div>
                    </div>
                    <div className="admin-form-grid admin-form-grid-2">
                      <div className="admin-form-group">
                        <label className="admin-label">knowledgeBaseId</label>
                        <input className="admin-input" value={form.knowledgeBaseId} onChange={(e) => setForm((prev) => ({ ...prev, knowledgeBaseId: e.target.value }))} />
                      </div>
                      <div className="admin-form-group">
                        <label className="admin-label">actionService</label>
                        <input className="admin-input" value={form.actionService} onChange={(e) => setForm((prev) => ({ ...prev, actionService: e.target.value }))} />
                      </div>
                    </div>
                    <div className="admin-form-grid admin-form-grid-3">
                      <div className="admin-form-group">
                        <label className="admin-label">mcpToolId</label>
                        <input className="admin-input" value={form.mcpToolId} onChange={(e) => setForm((prev) => ({ ...prev, mcpToolId: e.target.value }))} />
                      </div>
                      <div className="admin-form-group">
                        <label className="admin-label">topK</label>
                        <input className="admin-input" type="number" value={form.topK} onChange={(e) => setForm((prev) => ({ ...prev, topK: e.target.value }))} />
                      </div>
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">description</label>
                      <textarea className="admin-textarea" value={form.description} onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value }))} />
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">promptSnippet</label>
                      <textarea className="admin-textarea" value={form.promptSnippet} onChange={(e) => setForm((prev) => ({ ...prev, promptSnippet: e.target.value }))} />
                    </div>
                    <div className="admin-form-actions">
                      <button className="admin-btn admin-btn-primary" onClick={() => void submitCreate()} disabled={actionReq.loading}>
                        {actionReq.loading ? "创建中..." : "创建"}
                      </button>
                      <button className="admin-btn admin-btn-ghost" onClick={() => { setCreating(false); setForm(toForm(activeNode)); }}>取消</button>
                    </div>
                  </>
                )}
              </div>
            )}

            {/* Inline edit for existing node */}
            {!creating && activeNode && (
              <details className="intent-edit-details">
                <summary className="intent-edit-summary">编辑节点字段</summary>
                <div style={{ marginTop: 12 }}>
                  <div className="admin-form-grid admin-form-grid-2">
                    <div className="admin-form-group">
                      <label className="admin-label">nodeName *</label>
                      <input className="admin-input" value={form.nodeName} onChange={(e) => setForm((prev) => ({ ...prev, nodeName: e.target.value }))} />
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">parentId</label>
                      <input className="admin-input" value={form.parentId} onChange={(e) => setForm((prev) => ({ ...prev, parentId: e.target.value }))} />
                    </div>
                  </div>
                  <div className="admin-form-grid admin-form-grid-2">
                    <div className="admin-form-group">
                      <label className="admin-label">nodeType</label>
                      <CustomSelect
                        value={form.nodeType}
                        options={[
                          { value: "GROUP", label: "GROUP" },
                          { value: "RAG_QA", label: "RAG_QA" },
                          { value: "API_ACTION", label: "API_ACTION" },
                          { value: "CHITCHAT", label: "CHITCHAT" }
                        ]}
                        onChange={(v) => setForm((prev) => ({ ...prev, nodeType: v }))}
                      />
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">enabled</label>
                      <CustomSelect
                        value={form.enabled}
                        options={[
                          { value: "1", label: "启用" },
                          { value: "0", label: "停用" }
                        ]}
                        onChange={(v) => setForm((prev) => ({ ...prev, enabled: v }))}
                      />
                    </div>
                  </div>
                  <div className="admin-form-grid admin-form-grid-2">
                    <div className="admin-form-group">
                      <label className="admin-label">keywords（逗号分隔）</label>
                      <input className="admin-input" value={form.keywords} onChange={(e) => setForm((prev) => ({ ...prev, keywords: e.target.value }))} />
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">knowledgeBaseId</label>
                      <input className="admin-input" value={form.knowledgeBaseId} onChange={(e) => setForm((prev) => ({ ...prev, knowledgeBaseId: e.target.value }))} />
                    </div>
                  </div>
                  <div className="admin-form-grid admin-form-grid-2">
                    <div className="admin-form-group">
                      <label className="admin-label">actionService</label>
                      <input className="admin-input" value={form.actionService} onChange={(e) => setForm((prev) => ({ ...prev, actionService: e.target.value }))} />
                    </div>
                    <div className="admin-form-group">
                      <label className="admin-label">mcpToolId</label>
                      <input className="admin-input" value={form.mcpToolId} onChange={(e) => setForm((prev) => ({ ...prev, mcpToolId: e.target.value }))} />
                    </div>
                  </div>
                  <div className="admin-form-grid admin-form-grid-2">
                    <div className="admin-form-group">
                      <label className="admin-label">topK</label>
                      <input className="admin-input" type="number" value={form.topK} onChange={(e) => setForm((prev) => ({ ...prev, topK: e.target.value }))} />
                    </div>
                  </div>
                  <div className="admin-form-group">
                    <label className="admin-label">description</label>
                    <textarea className="admin-textarea" value={form.description} onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value }))} />
                  </div>
                  <div className="admin-form-group">
                    <label className="admin-label">promptSnippet</label>
                    <textarea className="admin-textarea" value={form.promptSnippet} onChange={(e) => setForm((prev) => ({ ...prev, promptSnippet: e.target.value }))} />
                  </div>
                  <div className="admin-form-actions">
                    <button className="admin-btn admin-btn-primary" onClick={() => void submitUpdate()} disabled={actionReq.loading}>
                      {actionReq.loading ? "保存中..." : "保存修改"}
                    </button>
                    <button className="admin-btn admin-btn-ghost" onClick={() => setForm(toForm(activeNode))}>重置</button>
                  </div>
                </div>
              </details>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function SearchIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
    </svg>
  );
}
