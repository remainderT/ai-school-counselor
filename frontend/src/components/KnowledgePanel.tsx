import { useEffect, useState } from "react";
import { apiDelete, apiGet, apiPost, apiPut } from "../lib/api";
import type { KnowledgeItem } from "../types";
import { useActionRequest } from "../hooks/useActionRequest";

interface KnowledgePanelProps {
  onOpenKnowledgeDocuments?: (knowledgeId: number) => void;
}

export function KnowledgePanel({ onOpenKnowledgeDocuments }: KnowledgePanelProps) {
  const [items, setItems] = useState<KnowledgeItem[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [showCreate, setShowCreate] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editingName, setEditingName] = useState("");
  const [editingDescription, setEditingDescription] = useState("");
  const [msg, setMsg] = useState("");
  const listReq = useActionRequest();
  const actionReq = useActionRequest();

  const load = async () => {
    const result = await listReq.runAction(() => apiGet<KnowledgeItem[]>("/api/rag/knowledge/list"), {
      errorFallback: "加载失败",
      onError: setMsg
    });
    if (result.ok) {
      setItems(result.data || []);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const create = async () => {
    if (!name.trim()) return;
    setMsg("");
    const result = await actionReq.runAction(
      () =>
        apiPost<number>("/api/rag/knowledge", {
          name: name.trim(),
          description: description.trim() || undefined
        }),
      {
        successToast: "创建知识库成功",
        errorFallback: "创建失败",
        onError: setMsg
      }
    );
    if (result.ok) {
      setName("");
      setDescription("");
      setShowCreate(false);
      await load();
    }
  };

  const remove = async (id: number) => {
    setMsg("");
    const result = await actionReq.runAction(() => apiDelete<void>(`/api/rag/knowledge/${id}`), {
      successToast: "删除知识库成功",
      errorFallback: "删除失败",
      onError: setMsg
    });
    if (result.ok) {
      await load();
    }
  };

  const openEditModal = (item: KnowledgeItem) => {
    setEditingId(item.id);
    setEditingName(item.name || "");
    setEditingDescription(item.description || "");
    setMsg("");
  };

  const closeEditModal = () => {
    setEditingId(null);
    setEditingName("");
    setEditingDescription("");
  };

  const update = async () => {
    if (editingId == null || !editingName.trim()) return;
    setMsg("");
    const result = await actionReq.runAction(
      () =>
        apiPut<void>(`/api/rag/knowledge/${editingId}`, {
          name: editingName.trim(),
          description: editingDescription.trim() || undefined
        }),
      {
        successToast: "更新知识库成功",
        errorFallback: "更新失败",
        onError: setMsg
      }
    );
    if (result.ok) {
      closeEditModal();
      await load();
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">知识库管理</h1>
          <p className="admin-page-desc">管理知识库条目，为检索与问答提供数据来源</p>
        </div>
        <div className="admin-page-actions">
          <button className="admin-btn admin-btn-primary" onClick={() => setShowCreate(true)}>
            + 创建知识库
          </button>
        </div>
      </div>

      {/* Create Modal */}
      {showCreate && (
        <div className="admin-upload-modal">
          <div className="admin-upload-modal-backdrop" onClick={() => setShowCreate(false)} />
          <div className="admin-upload-modal-panel" style={{ width: "min(520px, 100%)" }}>
            <div className="admin-upload-modal-header">
              <div>
                <h3 className="admin-card-title">创建知识库</h3>
                <p className="admin-card-desc">添加新的知识库，用于检索与问答</p>
              </div>
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={() => setShowCreate(false)}>✕</button>
            </div>
            <div className="admin-upload-modal-body">
              <div className="admin-form-grid" style={{ marginBottom: 0 }}>
                <div className="admin-form-group">
                  <label className="admin-label">名称 *</label>
                  <input className="admin-input" value={name} onChange={(e) => setName(e.target.value)} placeholder="输入知识库名称" autoFocus />
                </div>
                <div className="admin-form-group">
                  <label className="admin-label">描述</label>
                  <input className="admin-input" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="可选描述" />
                </div>
              </div>
            </div>
            <div className="admin-upload-modal-footer">
              <button className="admin-btn admin-btn-ghost" onClick={() => setShowCreate(false)}>取消</button>
              <button className="admin-btn admin-btn-primary" onClick={create} disabled={actionReq.loading || !name.trim()}>
                {actionReq.loading ? "创建中..." : "创建"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Edit Modal */}
      {editingId != null && (
        <div className="admin-upload-modal">
          <div className="admin-upload-modal-backdrop" onClick={closeEditModal} />
          <div className="admin-upload-modal-panel" style={{ width: "min(520px, 100%)" }}>
            <div className="admin-upload-modal-header">
              <div>
                <h3 className="admin-card-title">编辑知识库</h3>
                <p className="admin-card-desc">修改知识库名称和描述</p>
              </div>
              <button className="admin-btn admin-btn-ghost admin-btn-sm" onClick={closeEditModal}>✕</button>
            </div>
            <div className="admin-upload-modal-body">
              <div className="admin-form-grid" style={{ marginBottom: 0 }}>
                <div className="admin-form-group">
                  <label className="admin-label">名称 *</label>
                  <input className="admin-input" value={editingName} onChange={(e) => setEditingName(e.target.value)} placeholder="输入知识库名称" autoFocus />
                </div>
                <div className="admin-form-group">
                  <label className="admin-label">描述</label>
                  <input className="admin-input" value={editingDescription} onChange={(e) => setEditingDescription(e.target.value)} placeholder="可选描述" />
                </div>
              </div>
            </div>
            <div className="admin-upload-modal-footer">
              <button className="admin-btn admin-btn-ghost" onClick={closeEditModal}>取消</button>
              <button className="admin-btn admin-btn-primary" onClick={update} disabled={actionReq.loading || !editingName.trim()}>
                {actionReq.loading ? "保存中..." : "保存"}
              </button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className={`admin-alert ${msg.includes("成功") ? "admin-alert-success" : "admin-alert-error"}`}>{msg}</div>}
      {!msg && listReq.error && <div className="admin-alert admin-alert-error">{listReq.error}</div>}

      {/* Knowledge List */}
      {items.length > 0 ? (
        <div className="admin-card">
          <div className="admin-card-header">
            <h3 className="admin-card-title">知识库列表</h3>
            <span className="admin-badge">共 {items.length} 个</span>
          </div>
          <div className="admin-card-body" style={{ padding: 0 }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>描述</th>
                  <th style={{ width: 100 }}>文档数</th>
                  <th style={{ width: 180 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr
                    key={item.id}
                    className={onOpenKnowledgeDocuments ? "admin-table-row-clickable" : undefined}
                    onClick={() => onOpenKnowledgeDocuments?.(item.id)}
                  >
                    <td>
                      <strong className={onOpenKnowledgeDocuments ? "admin-link-strong" : undefined}>
                        {item.name}
                      </strong>
                    </td>
                    <td className="admin-table-text">{item.description || <span className="admin-muted">暂无描述</span>}</td>
                    <td className="admin-table-num">{item.documentCount ?? 0}</td>
                    <td>
                      <button
                        className="admin-btn admin-btn-ghost admin-btn-sm"
                        onClick={(e) => {
                          e.stopPropagation();
                          openEditModal(item);
                        }}
                        disabled={actionReq.loading}
                        style={{ marginRight: 8 }}
                      >
                        编辑
                      </button>
                      <button
                        className="admin-btn admin-btn-danger admin-btn-sm"
                        onClick={(e) => {
                          e.stopPropagation();
                          void remove(item.id);
                        }}
                        disabled={actionReq.loading}
                      >
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
            <div className="admin-empty-icon">📚</div>
            <p>暂无知识库，点击上方按钮创建</p>
          </div>
        )
      )}
    </div>
  );
}
