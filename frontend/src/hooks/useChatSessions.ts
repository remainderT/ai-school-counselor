import { useEffect, useMemo, useState } from "react";
import { apiDelete, apiGet, apiPost, apiPut } from "../lib/api";
import type { ConversationMessageItem, ConversationSessionItem, RetrievalMatch } from "../types";
import { useActionRequest } from "./useActionRequest";

export type ChatMsg = {
  role: "user" | "assistant";
  text: string;
  sources?: RetrievalMatch[];
  messageId?: number;
};

export type Conversation = {
  id: string;
  title: string;
  updatedAt: number;
  messages: ChatMsg[];
  userId: string;
  sessionId?: string;
  loaded: boolean;
  persisted: boolean;
};

const genId = () => `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

function toMillis(input?: string): number {
  if (!input) return Date.now();
  const value = Date.parse(input);
  return Number.isNaN(value) ? Date.now() : value;
}

function normalizeTitle(input?: string): string {
  const text = (input || "").trim();
  if (!text) return "新会话";
  return text.length > 18 ? `${text.slice(0, 18)}...` : text;
}

function toConversationMessage(item: ConversationMessageItem): ChatMsg {
  return {
    role: item.role,
    text: item.content || "",
    sources: item.sources || [],
    messageId: item.id
  };
}

export function useChatSessions(owner: string) {
  const [routePrefix, setRoutePrefix] = useState(owner);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string>("");
  const [notice, setNotice] = useState("");
  const listReq = useActionRequest();
  const historyReq = useActionRequest();
  const createReq = useActionRequest();
  const deleteReq = useActionRequest();
  const renameReq = useActionRequest();

  const activeConversation = useMemo(
    () => conversations.find((it) => it.id === activeId),
    [conversations, activeId]
  );

  const runtimeUserId = useMemo(() => {
    if (activeConversation?.userId) {
      return activeConversation.userId;
    }
    return `${(routePrefix || owner).trim() || "anonymous"}::${activeId || "default"}`;
  }, [activeConversation, routePrefix, owner, activeId]);

  const lastAssistant = useMemo(() => {
    const list = activeConversation?.messages || [];
    for (let i = list.length - 1; i >= 0; i -= 1) {
      if (list[i].role === "assistant") return list[i];
    }
    return undefined;
  }, [activeConversation]);

  const patchConversation = (conversationId: string, updater: (item: Conversation) => Conversation) => {
    setConversations((prev) =>
      prev
        .map((item) => (item.id === conversationId ? updater(item) : item))
        .sort((a, b) => b.updatedAt - a.updatedAt)
    );
  };

  const patchActive = (updater: (item: Conversation) => Conversation) => {
    if (!activeId) return;
    patchConversation(activeId, updater);
  };

  const listSessions = async (_prefix: string): Promise<ConversationSessionItem[]> => {
    return apiGet<ConversationSessionItem[]>("/api/rag/conversations/sessions");
  };

  const loadHistory = async (conversationId: string, sessionId: string) => {
    const result = await historyReq.runAction(() => {
      const query = new URLSearchParams({ sessionId, limit: "160" }).toString();
      return apiGet<ConversationMessageItem[]>(`/api/rag/conversations/history?${query}`);
    }, {
      errorFallback: "加载会话历史失败",
      onError: setNotice
    });
    if (result.ok) {
      const rows = result.data;
      const messages = (rows || []).map(toConversationMessage);
      setConversations((prev) =>
        prev.map((item) =>
          item.id === conversationId
            ? {
                ...item,
                messages,
                loaded: true,
                updatedAt: messages.length > 0 ? Date.now() : item.updatedAt
              }
            : item
        )
      );
    }
  };

  const refreshSessions = async (prefix: string, preferActive?: string) => {
    const result = await listReq.runAction(() => listSessions(prefix), {
      errorFallback: "加载会话列表失败",
      onError: setNotice
    });
    if (result.ok) {
      const rows = result.data;
      const remote = (rows || []).map((item) => ({
        id: item.sessionId,
        title: normalizeTitle(item.title),
        updatedAt: toMillis(item.updatedAt),
        messages: [] as ChatMsg[],
        userId: item.userId || `${prefix}::${item.sessionId}`,
        sessionId: item.sessionId,
        loaded: false,
        persisted: true
      }));

      setConversations((prev) => {
        const bySession = new Map(prev.filter((it) => it.sessionId).map((it) => [it.sessionId as string, it]));
        const byUser = new Map(prev.map((it) => [it.userId, it]));

        const merged: Conversation[] = remote.map((item) => {
          const existed = bySession.get(item.sessionId as string) || byUser.get(item.userId);
          if (!existed) {
            return item;
          }
          return {
            ...item,
            id: existed.id || item.id,
            messages: existed.loaded ? existed.messages : item.messages,
            loaded: existed.loaded
          };
        });

        const localOnly = prev.filter((it) => !it.persisted && it.messages.length === 0);
        return [...merged, ...localOnly].sort((a, b) => b.updatedAt - a.updatedAt);
      });

      setActiveId((prevActive) => {
        if (preferActive) {
          return preferActive;
        }
        if (prevActive && (rows || []).some((it) => it.sessionId === prevActive)) {
          return prevActive;
        }
        return rows && rows.length > 0 ? rows[0].sessionId : "";
      });
    }
  };

  const syncAfterSend = async (usedUserId: string, localConversationId: string) => {
    const prefix = (routePrefix || owner).trim() || "anonymous";
    try {
      const rows = await listSessions(prefix);
      const target = (rows || []).find((item) => item.userId === usedUserId);
      if (!target) {
        return;
      }
      setConversations((prev) =>
        prev.map((item) =>
          item.id === localConversationId
            ? {
                ...item,
                sessionId: target.sessionId,
                persisted: true,
                updatedAt: toMillis(target.updatedAt),
                title: normalizeTitle(target.title) || item.title
              }
            : item
        )
      );
    } catch {
      // ignore
    }
  };

  const newConversation = async (): Promise<Conversation | null> => {
    const localId = genId();
    const result = await createReq.runAction(
      () => apiPost<ConversationSessionItem>("/api/rag/conversations/sessions", { title: "新会话" }),
      {
        errorFallback: "创建会话失败",
        onError: setNotice
      }
    );
    if (!result.ok) {
      return null;
    }
    const created = result.data;
    const item: Conversation = {
      id: created.sessionId,
      title: normalizeTitle(created.title),
      updatedAt: toMillis(created.updatedAt),
      messages: [],
      userId: created.userId || `${(routePrefix || owner).trim() || "anonymous"}::${localId}`,
      sessionId: created.sessionId,
      loaded: true,
      persisted: true
    };
    setConversations((prev) => [item, ...prev.filter((it) => it.id !== item.id)]);
    setActiveId(item.id);
    setNotice("");
    return item;
  };

  const removeConversation = async (id: string) => {
    const target = conversations.find((it) => it.id === id);
    if (!target) {
      return;
    }
    if (target.persisted && target.sessionId) {
      const result = await deleteReq.runAction(
        () => apiDelete<void>(`/api/rag/conversations/sessions/${encodeURIComponent(target.sessionId as string)}`),
        {
          errorFallback: "删除会话失败",
          onError: setNotice
        }
      );
      if (!result.ok) {
        return;
      }
    }

    const next = conversations.filter((it) => it.id !== id);
    if (next.length === 0) {
      setConversations([]);
      setActiveId("");
      setNotice("");
      return;
    }
    setConversations(next);
    if (activeId === id) {
      setActiveId(next[0].id);
    }
    setNotice("");
  };

  const renameConversation = async (id: string, newTitle: string) => {
    const title = newTitle.trim() || "新会话";
    const target = conversations.find((item) => item.id === id);
    setConversations((prev) =>
      prev.map((item) => (item.id === id ? { ...item, title } : item))
    );
    if (!target?.persisted || !target.sessionId) {
      return;
    }
    const result = await renameReq.runAction(
      () => apiPut<ConversationSessionItem>(`/api/rag/conversations/sessions/${encodeURIComponent(target.sessionId as string)}`, {
        title
      }),
      {
        errorFallback: "重命名会话失败",
        onError: setNotice
      }
    );
    if (!result.ok) {
      setConversations((prev) =>
        prev.map((item) => (item.id === id ? { ...item, title: target.title } : item))
      );
      return;
    }
    setConversations((prev) =>
      prev.map((item) =>
        item.id === id
          ? {
              ...item,
              title: normalizeTitle(result.data.title),
              updatedAt: toMillis(result.data.updatedAt)
            }
          : item
      )
    );
  };

  const ensureTitle = (q: string, current: string) => {
    if (current !== "新会话") return current;
    return normalizeTitle(q);
  };

  useEffect(() => {
    setRoutePrefix(owner);
    void refreshSessions(owner);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [owner]);

  useEffect(() => {
    const target = conversations.find((item) => item.id === activeId);
    if (!target || target.loaded || !target.sessionId) {
      return;
    }
    void loadHistory(target.id, target.sessionId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId, conversations]);

  return {
    routePrefix,
    setRoutePrefix,
    conversations,
    activeId,
    setActiveId,
    loadingSessions: listReq.loading,
    creatingSession: createReq.loading,
    deletingSession: deleteReq.loading,
    renamingSession: renameReq.loading,
    notice,
    setNotice,
    activeConversation,
    runtimeUserId,
    lastAssistant,
    patchConversation,
    patchActive,
    refreshSessions,
    syncAfterSend,
    newConversation,
    removeConversation,
    renameConversation,
    ensureTitle
  };
}
