import { useEffect, useRef, useState, useCallback, type KeyboardEventHandler, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { apiPost, toErrorMessage } from "../lib/api";
import { formatSourceScore, normalizeSources, stripLegacyReferenceSection } from "../lib/chat-message";
import { createChatStream } from "../lib/sse";
import { pushToast } from "../lib/toast";
import type { FeedbackPayload, RetrievalMatch } from "../types";
import { MarkdownMessage } from "./MarkdownMessage";
import { useChatSessions, type Conversation } from "../hooks/useChatSessions";
import { useActionRequest } from "../hooks/useActionRequest";

/* ===== Inline SVG Icons ===== */
const SparkleIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
    <path d="M12 2L13.09 8.26L18 6L14.74 10.91L21 12L14.74 13.09L18 18L13.09 15.74L12 22L10.91 15.74L6 18L9.26 13.09L3 12L9.26 10.91L6 6L10.91 8.26L12 2Z" fill="currentColor" />
  </svg>
);
const SendIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M22 2 11 13" /><path d="m22 2-7 20-4-9-9-4z" />
  </svg>
);
const StopIcon = () => (
  <svg viewBox="0 0 24 24" fill="none">
    <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none" />
  </svg>
);
const CopyIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
);
const ThumbUpIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M7 10v12" /><path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z" />
  </svg>
);
const ThumbDownIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M17 14V2" /><path d="M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z" />
  </svg>
);
const ChevronDownIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="m6 9 6 6 6-6" />
  </svg>
);
const TrashIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="3 6 5 6 21 6" />
    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
  </svg>
);
const EditIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
  </svg>
);
const PlusIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
  </svg>
);
const CheckIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);
const XIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);
const RefreshIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" /><path d="M3 3v5h5" />
  </svg>
);
const MenuIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="3" y1="12" x2="21" y2="12" /><line x1="3" y1="6" x2="21" y2="6" /><line x1="3" y1="18" x2="21" y2="18" />
  </svg>
);

const CHAT_SIDEBAR_COLLAPSED_KEY = "chatSidebarCollapsed";
const SOURCE_PLACEHOLDER_TEXT = "暂无片段内容";
const SOURCE_CITATION_RE = /\[(\d+)]/g;

function getSourcePreviewText(text?: string) {
  const normalized = (text || "").replace(/\s+/g, " ").trim();
  if (!normalized) return SOURCE_PLACEHOLDER_TEXT;
  return normalized.length > 320 ? `${normalized.slice(0, 320)}...` : normalized;
}

function enhanceCitationLinks(text: string) {
  if (!text) return "";
  return text.replace(SOURCE_CITATION_RE, (full, rawIndex) => `[${rawIndex}](source-ref:${rawIndex})`);
}

/** 来源引用卡片 */
function SourceReferences({
  sources,
  activeIndex,
  onExpandedIndexChange
}: {
  sources: RetrievalMatch[];
  activeIndex?: number;
  onExpandedIndexChange?: (index: number) => void;
}) {
  const [expandedIndex, setExpandedIndex] = useState<number>(-1);

  useEffect(() => { setExpandedIndex(-1); }, [sources]);

  useEffect(() => {
    if (typeof activeIndex !== "number") return;
    if (activeIndex < 0 || activeIndex >= sources.length) return;
    setExpandedIndex(activeIndex);
  }, [activeIndex, sources.length]);

  const toggleExpanded = (index: number) => {
    setExpandedIndex((current) => {
      const next = current === index ? -1 : index;
      onExpandedIndexChange?.(next);
      return next;
    });
  };

  return (
    <div className="msg-sources">
      <div className="msg-sources-title">参考来源</div>
      <div className="msg-source-list">
        {sources.map((item, i) => {
          const previewText = getSourcePreviewText(item.textContent);
          const hasContent = previewText !== SOURCE_PLACEHOLDER_TEXT;
          const expanded = expandedIndex === i;
          const detailId = `msg-source-detail-${item.fileMd5 ?? "source"}-${item.chunkId ?? i}-${i}`;

          return (
            <div
              key={`${item.fileMd5 ?? item.chunkId ?? i}-${i}`}
              className={`msg-source-card${expanded ? " expanded" : ""}${hasContent ? "" : " empty"}`}
            >
              <button
                type="button"
                className="msg-source-trigger"
                aria-expanded={expanded}
                aria-controls={detailId}
                onClick={() => toggleExpanded(i)}
              >
                <div className="msg-source-card-head">
                  <div className="msg-source-main">
                    <div className="msg-source-index">{i + 1}</div>
                    <div className="msg-source-name-wrap">
                      <div className="msg-source-name" title={item.sourceFileName || "未知来源"}>
                        {item.sourceFileName || "未知来源"}
                      </div>
                      <div className="msg-source-hint">{hasContent ? "引用片段" : "暂无片段正文"}</div>
                    </div>
                  </div>
                  <div className="msg-source-aside">
                    <div className="msg-source-score">相关度 {formatSourceScore(item.relevanceScore)}</div>
                    <div className="msg-source-arrow" aria-hidden="true"><ChevronDownIcon /></div>
                  </div>
                </div>
              </button>
              {expanded ? (
                <div id={detailId} className="msg-source-preview">
                  <div className="msg-source-preview-head">
                    <div className="msg-source-preview-meta">
                      <span>相关度 {formatSourceScore(item.relevanceScore)}</span>
                    </div>
                  </div>
                  <div className="msg-source-preview-text">{previewText}</div>
                </div>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}

interface ChatWorkbenchProps {
  authUsername?: string;
  adminEntryButton?: React.ReactNode;
  onLogout?: () => void;
}

export function ChatWorkbench({ authUsername, adminEntryButton, onLogout }: ChatWorkbenchProps) {
  const loadSidebarCollapsed = () => {
    try { return window.localStorage.getItem(CHAT_SIDEBAR_COLLAPSED_KEY) === "1"; }
    catch { return false; }
  };

  const owner = (authUsername || "anonymous").trim() || "anonymous";
  const {
    conversations,
    activeId,
    setActiveId,
    creatingSession,
    deletingSession,
    renamingSession,
    notice,
    setNotice,
    activeConversation,
    runtimeUserId,
    patchConversation,
    syncAfterSend,
    newConversation,
    removeConversation,
    renameConversation,
    ensureTitle
  } = useChatSessions(owner);

  const [question, setQuestion] = useState("");
  const [pending, setPending] = useState(false);
  const [showScrollBottom, setShowScrollBottom] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(loadSidebarCollapsed);
  const [activeSourceMap, setActiveSourceMap] = useState<Record<string, number>>({});
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [feedbackState, setFeedbackState] = useState<Record<number, "up" | "down">>({});
  const feedbackReq = useActionRequest();

  const streamRef = useRef<ReturnType<typeof createChatStream> | null>(null);
  const msgListRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const editInputRef = useRef<HTMLInputElement | null>(null);

  const showNoticeError = (message: string) => {
    setNotice(message);
    pushToast(message, "error");
  };

  /* ===== Auto-resize textarea ===== */
  const adjustTextareaHeight = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, []);

  useEffect(() => { adjustTextareaHeight(); }, [question, adjustTextareaHeight]);

  useEffect(() => {
    try {
      window.localStorage.setItem(CHAT_SIDEBAR_COLLAPSED_KEY, sidebarCollapsed ? "1" : "0");
    } catch { /* ignore */ }
  }, [sidebarCollapsed]);

  /* ===== Scroll helpers ===== */
  const scrollToBottom = (behavior: ScrollBehavior = "smooth") => {
    const container = msgListRef.current;
    if (!container) return;
    container.scrollTo({ top: container.scrollHeight, behavior });
  };

  const handleScroll = useCallback(() => {
    const container = msgListRef.current;
    if (!container) return;
    const distance = container.scrollHeight - container.scrollTop - container.clientHeight;
    setShowScrollBottom(distance > 120);
  }, []);

  useEffect(() => {
    const container = msgListRef.current;
    if (!container) return;
    container.addEventListener("scroll", handleScroll);
    return () => container.removeEventListener("scroll", handleScroll);
  }, [handleScroll]);

  useEffect(() => { window.setTimeout(() => scrollToBottom("auto"), 0); }, [activeId]);

  useEffect(() => {
    const count = activeConversation?.messages.length || 0;
    if (count === 0) return;
    if (!showScrollBottom || pending) {
      window.setTimeout(() => scrollToBottom("auto"), 0);
    }
  }, [activeConversation?.messages.length, pending, showScrollBottom]);

  /* ===== Submit handlers ===== */
  const stopStreaming = () => {
    if (streamRef.current) {
      streamRef.current.cancel();
      streamRef.current = null;
      setPending(false);
      pushToast("已停止生成", "info");
    }
  };

  const submitStreamInternal = async (ask: string, conversation = activeConversation) => {
    if (!conversation || !ask.trim()) return;
    setNotice("");
    setPending(true);
    const usingUserId = conversation.userId || runtimeUserId;
    const currentId = conversation.id;

    if (streamRef.current) {
      streamRef.current.cancel();
      streamRef.current = null;
    }

    patchConversation(currentId, (item) => ({
      ...item,
      title: ensureTitle(ask, item.title),
      updatedAt: Date.now(),
      messages: [...item.messages, { role: "user", text: ask }, { role: "assistant", text: "" }]
    }));

    const stream = createChatStream(ask, {
      onText: (chunk) => {
        patchConversation(currentId, (item) => {
          const messages = [...item.messages];
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant") {
            last.text += chunk;
            last.text = stripLegacyReferenceSection(last.text);
          }
          return { ...item, updatedAt: Date.now(), messages, persisted: true, userId: usingUserId };
        });
      },
      onSources: (sources) => {
        patchConversation(currentId, (item) => {
          const messages = [...item.messages];
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant" && Array.isArray(sources)) {
            last.sources = normalizeSources(sources as RetrievalMatch[]);
            last.text = stripLegacyReferenceSection(last.text);
          }
          return { ...item, updatedAt: Date.now(), messages };
        });
      },
      onMessageId: (messageId) => {
        patchConversation(currentId, (item) => {
          const messages = [...item.messages];
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant") {
            last.messageId = messageId;
          }
          return { ...item, updatedAt: Date.now(), messages };
        });
      },
      onFinish: (payload) => {
        // finish 事件：更新对话标题
        if (payload.title) {
          patchConversation(currentId, (item) => ({
            ...item,
            title: payload.title || item.title
          }));
        }
        if (payload.messageId) {
          patchConversation(currentId, (item) => {
            const messages = [...item.messages];
            const last = messages[messages.length - 1];
            if (last && last.role === "assistant") {
              last.messageId = payload.messageId;
            }
            return { ...item, messages };
          });
        }
      },
      onDone: async () => {
        streamRef.current = null;
        setPending(false);
        await syncAfterSend(usingUserId, currentId);
      },
      onError: (err) => {
        streamRef.current = null;
        showNoticeError(err.message || "流式请求失败");
        setPending(false);
      }
    });
    streamRef.current = stream;

    try {
      await stream.start();
    } catch (e) {
      streamRef.current = null;
      showNoticeError(toErrorMessage(e, "流式请求失败"));
      setPending(false);
    }
  };

  const submitStream = async () => {
    const ask = question.trim();
    if (!ask) return;
    let target: Conversation | null = activeConversation ?? null;
    if (!target) {
      target = await newConversation();
      if (!target) return;
    }
    setQuestion("");
    await submitStreamInternal(ask, target);
  };

  const regenerateLastAnswer = async () => {
    if (!activeConversation) return;
    const users = activeConversation.messages.filter((item) => item.role === "user");
    const lastUser = users.length > 0 ? users[users.length - 1].text : "";
    if (!lastUser.trim()) {
      pushToast("没有可重新生成的问题", "error");
      return;
    }
    await submitStreamInternal(lastUser);
  };

  const copyAnswer = async (text: string, kind: "问题" | "回答" = "回答") => {
    try {
      await navigator.clipboard.writeText(text || "");
      pushToast(`已复制${kind}`, "success");
    } catch {
      pushToast("复制失败", "error");
    }
  };

  const submitFeedback = async (messageId: number, score: number) => {
    const payload: FeedbackPayload = {
      messageId,
      score,
      comment: "",
      userId: runtimeUserId
    };
    const prev = feedbackState[messageId];
    const newVal: "up" | "down" = score > 3 ? "up" : "down";
    setFeedbackState((s) => ({ ...s, [messageId]: prev === newVal ? undefined as unknown as "up" : newVal }));
    await feedbackReq.runAction(() => apiPost("/api/rag/chat/feedback", payload), {
      successToast: score > 3 ? "感谢好评 👍" : "感谢反馈",
      errorFallback: "反馈失败",
      onError: setNotice
    });
  };

  /* ===== Title editing ===== */
  const startEditing = (id: string, currentTitle: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingId(id);
    setEditingTitle(currentTitle);
    window.setTimeout(() => {
      editInputRef.current?.focus();
      editInputRef.current?.select();
    }, 0);
  };

  const commitEdit = async () => {
    if (editingId) await renameConversation(editingId, editingTitle);
    setEditingId(null);
    setEditingTitle("");
  };

  const cancelEdit = () => { setEditingId(null); setEditingTitle(""); };

  const onEditKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") { e.preventDefault(); void commitEdit(); }
    if (e.key === "Escape") { e.preventDefault(); cancelEdit(); }
  };

  const onQuestionKeyDown: KeyboardEventHandler<HTMLTextAreaElement> = async (e) => {
    if (e.key !== "Enter" || e.shiftKey || e.nativeEvent.isComposing) return;
    e.preventDefault();
    if (pending) { stopStreaming(); return; }
    await submitStream();
  };

  const hasMessages = (activeConversation?.messages.length || 0) > 0;
  const hasContent = question.trim().length > 0;

  const promptCards = [
    { icon: "📘", title: "教务与课程", text: "选课、考试、成绩、学籍这些问题怎么处理？" },
    { icon: "🏫", title: "校园生活", text: "宿舍报修、校园卡、校医院、校车这些问题该找谁？" },
    { icon: "🚀", title: "就业与升学", text: "保研、考研、就业手续、三方协议流程怎么走？" }
  ];

  /* ===== Compose input area ===== */
  const renderComposerInput = (emptyMode = false) => (
    <div className={emptyMode ? "chat-composer empty-mode" : "chat-composer"}>
      <div className="composer-card">
        <textarea
          ref={textareaRef}
          className="composer-text"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={onQuestionKeyDown}
          placeholder="输入你的问题就行"
          rows={1}
        />
        <div className="composer-actions">
          {!emptyMode && (
            <button
              className="composer-extra-btn"
              disabled={pending || !activeConversation?.messages.length}
              onClick={() => void regenerateLastAnswer()}
              title="重新生成"
            >
              <RefreshIcon />
              <span>重新生成</span>
            </button>
          )}
          <button
            className={`composer-send-btn ${pending ? "streaming" : hasContent ? "ready" : "idle"}`}
            onClick={pending ? stopStreaming : submitStream}
            disabled={!hasContent && !pending}
            aria-label={pending ? "停止生成" : "发送"}
          >
            {pending ? <StopIcon /> : <SendIcon />}
          </button>
        </div>
      </div>
    </div>
  );

  return (
    <section className={sidebarCollapsed ? "chat-layout sidebar-collapsed" : "chat-layout"}>
      {/* ===== Sidebar toggle button (mobile/collapsed) ===== */}
      <button
        className={sidebarCollapsed ? "chat-sidebar-handle collapsed" : "chat-sidebar-handle"}
        type="button"
        onClick={() => setSidebarCollapsed((prev) => !prev)}
        title={sidebarCollapsed ? "展开侧边栏" : "收起侧边栏"}
        aria-label={sidebarCollapsed ? "展开侧边栏" : "收起侧边栏"}
      >
        <span className="sidebar-handle-arrow" aria-hidden="true">
          {sidebarCollapsed ? "›" : "‹"}
        </span>
      </button>

      {/* ===== Session Sidebar ===== */}
      <aside className="chat-side">
        {/* Brand logo area */}
        <div className="chat-side-brand">
          <div className="chat-brand-logo">B</div>
          <div className="chat-brand-name">BUAA问答助手</div>
          <button
            className="chat-sidebar-close-btn"
            onClick={() => setSidebarCollapsed(true)}
            title="收起"
            aria-label="收起侧边栏"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M11 17l-5-5 5-5" /><path d="M18 17l-5-5 5-5" />
            </svg>
          </button>
        </div>

        {/* New conversation button */}
        <div className="chat-side-head">
          <button className="chat-new-btn" onClick={() => void newConversation()} disabled={creatingSession}>
            <PlusIcon />
            <span>{creatingSession ? "创建中..." : "新建对话"}</span>
          </button>
        </div>

        {/* Session list */}
        <div className="chat-session-list">
          {conversations.length === 0 && (
            <div className="chat-session-empty">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" style={{ opacity: 0.3 }}>
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
              <span>暂无对话历史</span>
            </div>
          )}
          {conversations.map((item) => {
            const isEditing = editingId === item.id;
            const isActive = item.id === activeId;
            return (
              <div
                key={item.id}
                className={isActive ? "chat-session-card active" : "chat-session-card"}
                onClick={() => { if (!isEditing) setActiveId(item.id); }}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => { if (e.key === "Enter" && !isEditing) setActiveId(item.id); }}
              >
                {isEditing ? (
                  <div className="chat-session-edit" onClick={(e) => e.stopPropagation()}>
                    <input
                      ref={editInputRef}
                      className="chat-session-edit-input"
                      value={editingTitle}
                      onChange={(e) => setEditingTitle(e.target.value)}
                      onKeyDown={onEditKeyDown}
                      onBlur={() => void commitEdit()}
                      maxLength={40}
                    />
                    <div className="chat-session-edit-actions">
                      <button className="chat-session-edit-btn confirm" onClick={() => void commitEdit()} title="保存" disabled={renamingSession}>
                        <CheckIcon />
                      </button>
                      <button className="chat-session-edit-btn cancel" onClick={cancelEdit} title="取消" disabled={renamingSession}>
                        <XIcon />
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="chat-session-info">
                      <div className="chat-session-title">{item.title}</div>
                      <div className="chat-session-time">{formatRelativeTime(item.updatedAt)}</div>
                    </div>
                    <div className="chat-session-actions">
                      <button
                        className="chat-session-action-btn"
                        onClick={(e) => startEditing(item.id, item.title, e)}
                        title="重命名"
                      >
                        <EditIcon />
                      </button>
                      <button
                        className="chat-session-action-btn delete"
                        onClick={(e) => { e.stopPropagation(); void removeConversation(item.id); }}
                        title="删除对话"
                        disabled={deletingSession}
                      >
                        <TrashIcon />
                      </button>
                    </div>
                  </>
                )}
              </div>
            );
          })}
        </div>

        {/* Sidebar Footer */}
        <div className="sidebar-footer-wrap">
          {adminEntryButton && (
            <div className="chat-side-admin">{adminEntryButton}</div>
          )}
          <div className="sidebar-footer">
            <div className="sidebar-user">
              <div className="sidebar-user-avatar">{owner.charAt(0).toUpperCase()}</div>
              <span className="sidebar-user-name">{owner}</span>
            </div>
            {onLogout && (
              <button className="sidebar-logout-btn" onClick={onLogout} title="退出登录">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                  <polyline points="16 17 21 12 16 7" />
                  <line x1="21" y1="12" x2="9" y2="12" />
                </svg>
              </button>
            )}
          </div>
        </div>
      </aside>

      {/* ===== Chat Main ===== */}
      <div className={hasMessages ? "chat-main" : "chat-main empty-mode"}>
        {/* Mobile menu button (only visible when sidebar collapsed) */}
        {sidebarCollapsed && (
          <button
            className="chat-mobile-menu-btn"
            onClick={() => setSidebarCollapsed(false)}
            aria-label="打开侧边栏"
          >
            <MenuIcon />
          </button>
        )}

        {/* Welcome Screen */}
        {!hasMessages && (
          <>
            <div className="chat-welcome">
              <div className="chat-welcome-inner">
                <div className="chat-welcome-badge">
                  <span className="chat-welcome-badge-dot" />
                  BUAA 问答助手
                </div>
                <h2 className="chat-welcome-title">
                  你好，<span className="chat-welcome-name">{owner}</span>
                </h2>
                <p className="chat-welcome-desc">
                  学校里的任何问题都可以提问，我会给你清晰、可执行的答案。
                </p>

                {/* Input in welcome mode */}
                {renderComposerInput(true)}

                {/* Quick prompt cards */}
                <div className="chat-prompt-grid">
                  {promptCards.map((card) => (
                    <button
                      key={card.title}
                      className="chat-prompt-card"
                      onClick={() => setQuestion(card.text)}
                    >
                      <div className="chat-prompt-icon">{card.icon}</div>
                      <div className="chat-prompt-title">{card.title}</div>
                      <div className="chat-prompt-text">{card.text}</div>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </>
        )}

        {/* Notice */}
        {notice && (
          <div className="chat-notice" role="alert">
            <span className={notice.includes("成功") ? "notice-ok" : "notice-error"}>{notice}</span>
            <button className="notice-close" onClick={() => setNotice("")} aria-label="关闭">×</button>
          </div>
        )}

        {/* Message List */}
        {hasMessages && (
          <div className="msg-list" ref={msgListRef}>
            <div className="msg-list-inner">
              {(activeConversation?.messages || []).map((msg, idx) => {
                const isUser = msg.role === "user";
                const isAssistant = msg.role === "assistant";
                const isWaiting = isAssistant && !msg.text;
                const isStreaming = isAssistant && pending && idx === (activeConversation?.messages.length || 0) - 1;
                const isLast = idx === (activeConversation?.messages.length || 0) - 1;
                const displayText = isAssistant ? stripLegacyReferenceSection(msg.text) : (msg.text || "");
                const linkedDisplayText = isAssistant ? enhanceCitationLinks(displayText || "") : displayText;
                const displaySources = isAssistant ? normalizeSources(msg.sources).slice(0, 5) : [];
                const canCopy = Boolean(displayText);
                const messageKey = `${msg.role}-${idx}`;
                const feedbackVal = msg.messageId ? feedbackState[msg.messageId] : undefined;

                return (
                  <article key={messageKey} className={`msg ${msg.role}${isLast ? " msg-last" : ""}`}>
                    {isUser ? (
                      /* ── User message ── */
                      <div className="msg-user-wrap">
                        <div className="msg-user-bubble">
                          <div className="msg-plain-text">{msg.text || "..."}</div>
                        </div>
                        {canCopy && (
                          <div className="msg-actions">
                            <button
                              className="msg-action-btn"
                              onClick={() => void copyAnswer(displayText, "问题")}
                              title="复制问题"
                            >
                              <CopyIcon />
                            </button>
                          </div>
                        )}
                      </div>
                    ) : (
                      /* ── Assistant message ── */
                      <div className="msg-assistant-wrap">
                        <div className="msg-assistant-avatar">
                          <SparkleIcon />
                        </div>
                        <div className="msg-assistant-content">
                          {isWaiting ? (
                            <div className="msg-waiting">
                              <span className="msg-waiting-dot" />
                              <span className="msg-waiting-dot" />
                              <span className="msg-waiting-dot" />
                            </div>
                          ) : (
                            <div className="msg-bubble assistant">
                              <MarkdownMessage
                                content={linkedDisplayText || "..."}
                                onSourceCitationClick={(sourceIndex) => {
                                  const nextIndex = sourceIndex - 1;
                                  setActiveSourceMap((current) => ({ ...current, [messageKey]: nextIndex }));
                                }}
                              />
                              {isStreaming && (
                                <span className="msg-streaming-cursor" aria-hidden="true" />
                              )}
                            </div>
                          )}

                          {/* Sources */}
                          {displaySources.length > 0 && (
                            <SourceReferences
                              sources={displaySources}
                              activeIndex={activeSourceMap[messageKey]}
                              onExpandedIndexChange={(nextIndex) => {
                                setActiveSourceMap((current) => ({ ...current, [messageKey]: nextIndex }));
                              }}
                            />
                          )}

                          {/* Action buttons */}
                          {(canCopy || (isAssistant && msg.messageId)) && (
                            <div className={`msg-actions${isLast ? " visible" : ""}`}>
                              {canCopy && (
                                <button
                                  className="msg-action-btn"
                                  onClick={() => void copyAnswer(displayText)}
                                  title="复制回答"
                                >
                                  <CopyIcon />
                                </button>
                              )}
                              {isAssistant && msg.messageId && (
                                <>
                                  <button
                                    className={`msg-action-btn${feedbackVal === "up" ? " active-like" : ""}`}
                                    onClick={() => void submitFeedback(msg.messageId!, 5)}
                                    title="有帮助"
                                  >
                                    <ThumbUpIcon />
                                  </button>
                                  <button
                                    className={`msg-action-btn${feedbackVal === "down" ? " active-dislike" : ""}`}
                                    onClick={() => void submitFeedback(msg.messageId!, 1)}
                                    title="没帮助"
                                  >
                                    <ThumbDownIcon />
                                  </button>
                                </>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </article>
                );
              })}
            </div>
          </div>
        )}

        {/* Scroll to bottom */}
        {showScrollBottom && (
          <button className="scroll-bottom-btn" onClick={() => scrollToBottom("smooth")} aria-label="回到底部">
            <ChevronDownIcon />
          </button>
        )}

        {/* Composer (when has messages) */}
        {hasMessages && renderComposerInput()}
      </div>
    </section>
  );
}

function formatRelativeTime(ts: number): string {
  const diff = Date.now() - ts;
  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(diff / 86400000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  if (hours < 24) return `${hours} 小时前`;
  if (days < 7) return `${days} 天前`;
  return new Date(ts).toLocaleDateString("zh-CN", { month: "short", day: "numeric" });
}
