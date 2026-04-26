import { apiUrl } from "./api";
import type { StreamEvent } from "../types";
import { loadAuth } from "./auth-storage";

export interface StreamHandlers {
  onEvent?: (payload: StreamEvent) => void;
  onText?: (text: string) => void;
  onSources?: (sources: unknown) => void;
  onMessageId?: (messageId: number) => void;
  onFinish?: (payload: FinishPayload) => void;
  onDone?: () => void;
  onError?: (error: Error) => void;
}

export interface FinishPayload {
  title?: string;
  messageId?: number;
}

/** 尝试将 SSE data 字段解析为 JSON；解析失败时返回原始字符串 */
function tryParseJson(text: string): unknown {
  if (text === "") return text;
  try {
    return JSON.parse(text);
  } catch {
    // 非 JSON 内容，按原始文本返回
    return text;
  }
}

/** 将解析出的事件分派到对应的 handler 回调 */
function routeEvent(name: string, payload: unknown, handlers: StreamHandlers): void {
  handlers.onEvent?.({ event: name, data: payload });
  switch (name) {
    case "message": {
      // 新格式：{type: "response", delta: "..."} 或旧格式直接字符串
      if (payload && typeof payload === "object" && "delta" in payload) {
        const msgPayload = payload as { type?: string; delta?: string };
        if (msgPayload.type === "response" || !msgPayload.type) {
          handlers.onText?.(msgPayload.delta ?? "");
        }
        // type === "think" 时忽略（本项目不支持深度思考，但预留兼容）
      } else {
        handlers.onText?.(String(payload));
      }
      break;
    }
    case "meta": {
      const messageId = extractMessageId(payload);
      if (messageId !== null) {
        handlers.onMessageId?.(messageId);
      }
      break;
    }
    case "sources":
      handlers.onSources?.(payload);
      break;
    case "finish": {
      // 新格式：{title: "...", messageId: 123}
      const finishPayload = payload && typeof payload === "object" ? payload as FinishPayload : {};
      handlers.onFinish?.(finishPayload);
      // 如果 finish 事件里有 messageId，也通知 onMessageId（兼容性）
      if (finishPayload.messageId) {
        handlers.onMessageId?.(finishPayload.messageId);
      }
      break;
    }
    case "messageId":
      handlers.onMessageId?.(Number(payload));
      break;
    case "done":
      handlers.onDone?.();
      break;
    case "error":
      handlers.onError?.(new Error(String(payload)));
      break;
  }
}

function extractMessageId(payload: unknown): number | null {
  if (typeof payload === "number" && Number.isFinite(payload)) {
    return payload;
  }
  if (typeof payload === "string") {
    const value = Number(payload);
    return Number.isFinite(value) ? value : null;
  }
  if (payload && typeof payload === "object" && "messageId" in payload) {
    const value = Number((payload as { messageId?: unknown }).messageId);
    return Number.isFinite(value) ? value : null;
  }
  return null;
}

function hasReceivedEventFlag(error: unknown): boolean {
  if (!error || typeof error !== "object" || !("receivedAnyEvent" in error)) {
    return false;
  }
  return Boolean((error as { receivedAnyEvent?: unknown }).receivedAnyEvent);
}

export function createChatStream(message: string, handlers: StreamHandlers) {
  const abortCtl = new AbortController();
  const query = new URLSearchParams({ message }).toString();
  const endpoint = apiUrl(`/api/rag/chat/stream?${query}`);

  const attempt = async () => {
    const auth = loadAuth();
    const resp = await fetch(endpoint, {
      method: "GET",
      headers: {
        Accept: "text/event-stream",
        ...(auth ? { username: auth.username, token: auth.token } : {})
      },
      signal: abortCtl.signal
    });
    if (!resp.ok || !resp.body) {
      throw new Error(`流式请求失败 (${resp.status})`);
    }

    const reader = resp.body.getReader();
    const utf8 = new TextDecoder("utf-8");
    let pending = "";
    let currentEvent = "message";
    let dataChunks: string[] = [];
    let receivedAnyEvent = false;

    const dispatchBuffered = () => {
      if (dataChunks.length === 0) {
        currentEvent = "message";
        return;
      }
      receivedAnyEvent = true;
      const merged = dataChunks.join("\n");
      routeEvent(currentEvent, tryParseJson(merged), handlers);
      currentEvent = "message";
      dataChunks = [];
    };

    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          dispatchBuffered();
          return;
        }
        pending += utf8.decode(value, { stream: true });

        const segments = pending.split(/\r?\n/);
        pending = segments.pop() ?? "";

        for (const seg of segments) {
          if (seg === "") {
            dispatchBuffered();
          } else if (seg[0] === ":") {
            // SSE 注释行，忽略
          } else if (seg.startsWith("event:")) {
            currentEvent = seg.substring(6).trim();
          } else if (seg.startsWith("data:")) {
            dataChunks.push(seg.substring(5).trim());
          }
        }
      }
    } catch (error) {
      const wrapped = error instanceof Error ? error : new Error(String(error));
      (wrapped as Error & { receivedAnyEvent?: boolean }).receivedAnyEvent = receivedAnyEvent;
      throw wrapped;
    }
  };

  const start = async () => {
    const MAX_RETRIES = 2;
    for (let tries = 0; ; tries++) {
      try {
        await attempt();
        return;
      } catch (err) {
        const receivedAnyEvent = hasReceivedEventFlag(err);
        // 已收到任何事件后断流：不自动重试，避免将同一答案从头再拼接一遍。
        if (abortCtl.signal.aborted || tries >= MAX_RETRIES || receivedAnyEvent) throw err;
        // 指数退避：500ms → 1s → 2s
        await new Promise<void>((ok) => setTimeout(ok, 500 * (1 << tries)));
      }
    }
  };

  return { start, cancel: () => abortCtl.abort() };
}
