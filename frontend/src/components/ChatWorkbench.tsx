import { useEffect, useRef, useState, useCallback, type KeyboardEventHandler, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { apiPost, toErrorMessage } from "../lib/api";
import { createChatStream } from "../lib/sse";
import { pushToast } from "../lib/toast";
import type { FeedbackPayload, RetrievalMatch } from "../types";
import { MarkdownMessage } from "./MarkdownMessage";
import { useChatSessions } from "../hooks/useChatSessions";
import { useActionRequest } from "../hooks/useActionRequest";

/* ===== SVG Icons (inline, no deps) ===== */
const SparkleIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
    <path d="M12 2L13.09 8.26L18 6L14.74 10.91L21 12L14.74 13.09L18 18L13.09 15.74L12 22L10.91 15.74L6 18L9.26 13.09L3 12L9.26 10.91L6 6L10.91 8.26L12 2Z" fill="currentColor" />
  </svg>
);
const SendIcon = () => (
  <svg viewBox="0 0 24 24"><path d="M22 2 11 13" /><path d="m22 2-7 20-4-9-9-4z" /></svg>
);
const StopIcon = () => (
  <svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none" /></svg>
);
const CopyIcon = () => (
  <svg viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>
);
const ThumbUpIcon = () => (
  <svg viewBox="0 0 24 24"><path d="M7 10v12" /><path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z" /></svg>
);
const ThumbDownIcon = () => (
  <svg viewBox="0 0 24 24"><path d="M17 14V2" /><path d="M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z" /></svg>
);
const ChevronDownIcon = () => (
  <svg viewBox="0 0 24 24"><path d="m6 9 6 6 6-6" /></svg>
);
const PlusIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" /></svg>
);
const TrashIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="3 6 5 6 21 6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
);
const EditIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" /><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" /></svg>
);
const CheckIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
);
const XIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
);
const ChatBubbleIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>
);

interface ChatWorkbenchProps {
  authUsername?: string;
  adminEntryButton?: React.ReactNode;
  onLogout?: () => void;
}

export function ChatWorkbench({ authUsername, adminEntryButton, onLogout }: ChatWorkbenchProps) {
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
    patchActive,
    syncAfterSend,
    newConversation,
    removeConversation,
    renameConversation,
    ensureTitle
  } = useChatSessions(owner);

  const [question, setQuestion] = useState("");
  const [pending, setPending] = useState(false);
  const [showScrollBottom, setShowScrollBottom] = useState(false);
  // 编辑标题状态
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState("");
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

  useEffect(() => {
    adjustTextareaHeight();
  }, [question, adjustTextareaHeight]);

  /* ===== Scroll helpers ===== */
  const scrollToBottom = (behavior: ScrollBehavior = "smooth") => {
    const container = msgListRef.current;
    if (!container) return;
    container.scrollTo({ top: container.scrollHeight, behavior });
  };

  const handleScroll = () => {
    const container = msgListRef.current;
    if (!container) return;
    const distance = container.scrollHeight - container.scrollTop - container.clientHeight;
    setShowScrollBottom(distance > 120);
  };

  useEffect(() => {
    const container = msgListRef.current;
    if (!container) return;
    container.addEventListener("scroll", handleScroll);
    return () => container.removeEventListener("scroll", handleScroll);
  }, []);

  useEffect(() => {
    window.setTimeout(() => scrollToBottom("auto"), 0);
  }, [activeId]);

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

  const submitStreamInternal = async (ask: string) => {
    if (!activeConversation || !ask.trim()) return;
    setNotice("");
    setPending(true);
    const usingUserId = runtimeUserId;
    const currentId = activeConversation.id;

    if (streamRef.current) {
      streamRef.current.cancel();
      streamRef.current = null;
    }

    patchActive((item) => ({
      ...item,
      title: ensureTitle(ask, item.title),
      updatedAt: Date.now(),
      messages: [...item.messages, { role: "user", text: ask }, { role: "assistant", text: "" }]
    }));

    const stream = createChatStream(ask, usingUserId, {
      onText: (chunk) => {
        patchActive((item) => {
          const messages = [...item.messages];
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant") {
            last.text += chunk;
          }
          return { ...item, updatedAt: Date.now(), messages, persisted: true, userId: usingUserId };
        });
      },
      onSources: (sources) => {
        patchActive((item) => {
          const messages = [...item.messages];
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant" && Array.isArray(sources)) {
            last.sources = sources as RetrievalMatch[];
          }
          return { ...item, updatedAt: Date.now(), messages };
        });
      },
      onMessageId: (messageId) => {
        patchActive((item) => {
          const messages = [...item.messages];
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant") {
            last.messageId = messageId;
          }
          return { ...item, updatedAt: Date.now(), messages };
        });
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
    setQuestion("");
    await submitStreamInternal(ask);
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

  const copyAnswer = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text || "");
      pushToast("已复制回答", "success");
    } catch {
      pushToast("复制失败", "error");
    }
  };

  const submitFeedback = async (messageId: string, score: number) => {
    const payload: FeedbackPayload = {
      messageId,
      score,
      comment: "",
      userId: runtimeUserId
    };
    await feedbackReq.runAction(() => apiPost("/api/rag/chat/feedback", payload), {
      successToast: score > 3 ? "感谢好评" : "感谢反馈",
      errorFallback: "反馈失败",
      onError: setNotice
    });
  };

  /* ===== Title editing helpers ===== */
  const startEditing = (id: string, currentTitle: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingId(id);
    setEditingTitle(currentTitle);
    // 等 input 渲染后聚焦并全选
    window.setTimeout(() => {
      editInputRef.current?.focus();
      editInputRef.current?.select();
    }, 0);
  };

  const commitEdit = async () => {
    if (editingId) {
      await renameConversation(editingId, editingTitle);
    }
    setEditingId(null);
    setEditingTitle("");
  };

  const cancelEdit = () => {
    setEditingId(null);
    setEditingTitle("");
  };

  const onEditKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") { e.preventDefault(); void commitEdit(); }
    if (e.key === "Escape") { e.preventDefault(); cancelEdit(); }
  };

  const onQuestionKeyDown: KeyboardEventHandler<HTMLTextAreaElement> = async (e) => {
    if (e.key !== "Enter" || e.shiftKey || e.nativeEvent.isComposing) return;
    e.preventDefault();
    if (pending) {
      stopStreaming();
      return;
    }
    await submitStream();
  };

  const hasMessages = (activeConversation?.messages.length || 0) > 0;
  const hasContent = question.trim().length > 0;

  const promptCards = [
    { icon: "📋", title: "学业规划", text: "帮我制定本学期的学习计划，合理安排课程和复习时间。" },
    { icon: "📖", title: "课程答疑", text: "解释一下这个知识点的核心概念，并举例说明应用场景。" },
    { icon: "🎓", title: "考试准备", text: "帮我梳理这门课的重点内容，列出高频考点和复习建议。" }
  ];

  return (
    <section className="chat-layout">
      {/* ===== Session Sidebar ===== */}
      <aside className="chat-side">
        <div className="chat-side-head">
          <button className="chat-new-btn" onClick={() => void newConversation()} disabled={creatingSession}>
            <PlusIcon />
            <span>{creatingSession ? "创建中..." : "新建对话"}</span>
          </button>
        </div>

        <div className="chat-session-list">
          {conversations.length === 0 && (
            <div className="chat-session-empty">
              <ChatBubbleIcon />
              <span>暂无对话</span>
            </div>
          )}
          {conversations.map((item) => {
            const isEditing = editingId === item.id;
            return (
              <div
                key={item.id}
                className={item.id === activeId ? "chat-session-card active" : "chat-session-card"}
                onClick={() => { if (!isEditing) setActiveId(item.id); }}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => { if (e.key === "Enter" && !isEditing) setActiveId(item.id); }}
              >
                {isEditing ? (
                  /* ---- 编辑模式 ---- */
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
                  /* ---- 正常模式 ---- */
                  <>
                    <div className="chat-session-info">
                      <div className="chat-session-title">{item.title}</div>
                      <div className="chat-session-time">{new Date(item.updatedAt).toLocaleString()}</div>
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

        {/* Sidebar Footer — Admin Entry + User & Logout */}
        <div className="sidebar-footer-wrap">
          {adminEntryButton && (
            <div className="chat-side-admin">
              {adminEntryButton}
            </div>
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

      {/* ===== Chat Main Area ===== */}
      <div className={hasMessages ? "chat-main" : "chat-main empty-mode"}>
        {/* Welcome Screen */}
        {!hasMessages && (
          <>
            <div className="chat-empty">
              <div className="chat-empty-badge">BUAA AI 辅导员</div>
              <h2>你好，<span>{owner}</span></h2>
              <p>有什么学业问题需要帮助？我可以为你解答课程疑问、规划学习路径。</p>
            </div>

            <div className="chat-composer empty-mode">
              <div className="composer-card">
                <textarea
                  ref={textareaRef}
                  className="composer-text"
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  onKeyDown={onQuestionKeyDown}
                  placeholder="在这里输入你的问题..."
                  rows={1}
                />
                <div className="composer-actions">
                  <div className="composer-hint">
                    <kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行
                    {pending && <span style={{ marginLeft: 8, color: "var(--brand)" }}>生成中...</span>}
                  </div>
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
          </>
        )}

        {/* Notice */}
        {notice && <p style={{ padding: "0 24px", fontSize: 13 }} className={notice.includes("成功") ? "ok" : "error"}>{notice}</p>}

        {/* Message List */}
        {hasMessages && (
          <div className="msg-list" ref={msgListRef}>
            <div className="msg-list-inner">
              {(activeConversation?.messages || []).map((msg, idx) => {
                const isUser = msg.role === "user";
                const isAssistant = msg.role === "assistant";
                const isWaiting = isAssistant && !msg.text;
                const isLast = idx === (activeConversation?.messages.length || 0) - 1;

                return (
                  <article key={`${msg.role}-${idx}`} className={`msg ${msg.role}`}>
                    <div className="msg-avatar">
                      {isUser ? owner.charAt(0).toUpperCase() : <SparkleIcon />}
                    </div>
                    <div className="msg-content">
                      <div className="msg-bubble">
                        {isWaiting ? (
                          <div className="msg-waiting">
                            <span className="msg-waiting-dot" />
                            <span className="msg-waiting-dot" />
                            <span className="msg-waiting-dot" />
                          </div>
                        ) : isAssistant ? (
                          <MarkdownMessage content={msg.text || "..."} />
                        ) : (
                          <div style={{ whiteSpace: "pre-wrap", wordBreak: "break-word" }}>{msg.text || "..."}</div>
                        )}
                      </div>

                      {/* Sources */}
                      {msg.sources?.length ? (
                        <div className="msg-sources">
                          {msg.sources.slice(0, 3).map((item, i) => (
                            <span key={`${item.chunkId ?? i}-${i}`} className="msg-source-chip">
                              {item.sourceFileName || "未知来源"} · {item.relevanceScore?.toFixed(2) ?? "-"}
                            </span>
                          ))}
                        </div>
                      ) : null}

                      {/* Actions */}
                      {isAssistant && msg.text ? (
                        <div className={`msg-actions${isLast ? " visible" : ""}`}>
                          <button className="msg-action-btn" onClick={() => void copyAnswer(msg.text)} title="复制">
                            <CopyIcon />
                          </button>
                          {msg.messageId && (
                            <>
                              <button
                                className="msg-action-btn"
                                onClick={() => void submitFeedback(msg.messageId!, 5)}
                                title="有帮助"
                              >
                                <ThumbUpIcon />
                              </button>
                              <button
                                className="msg-action-btn"
                                onClick={() => void submitFeedback(msg.messageId!, 1)}
                                title="没帮助"
                              >
                                <ThumbDownIcon />
                              </button>
                            </>
                          )}
                        </div>
                      ) : null}
                    </div>
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
        {hasMessages && (
          <div className="chat-composer">
            <div className="composer-card">
              <textarea
                ref={textareaRef}
                className="composer-text"
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                onKeyDown={onQuestionKeyDown}
                placeholder="输入你的问题..."
                rows={1}
              />
              <div className="composer-actions">
                <div className="composer-hint">
                  <kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行
                  {pending && <span style={{ marginLeft: 8, color: "#3b82f6" }}>生成中...</span>}
                </div>
                <button
                  className="composer-extra-btn"
                  disabled={pending || !activeConversation?.messages.length}
                  onClick={() => void regenerateLastAnswer()}
                >
                  重新生成
                </button>
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
        )}
      </div>
    </section>
  );
}
