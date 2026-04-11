import { apiUrl } from "./api";
import type { StreamEvent } from "../types";
import { loadAuth } from "./auth-storage";

export interface StreamHandlers {
  onEvent?: (payload: StreamEvent) => void;
  onText?: (text: string) => void;
  onSources?: (sources: unknown) => void;
  onMessageId?: (messageId: number) => void;
  onDone?: () => void;
  onError?: (error: Error) => void;
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
    case "message":
      handlers.onText?.(String(payload));
      break;
    case "sources":
      handlers.onSources?.(payload);
      break;
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

    const dispatchBuffered = () => {
      if (dataChunks.length === 0) {
        currentEvent = "message";
        return;
      }
      const merged = dataChunks.join("\n");
      routeEvent(currentEvent, tryParseJson(merged), handlers);
      currentEvent = "message";
      dataChunks = [];
    };

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
  };

  const start = async () => {
    const MAX_RETRIES = 2;
    for (let tries = 0; ; tries++) {
      try {
        await attempt();
        return;
      } catch (err) {
        if (abortCtl.signal.aborted || tries >= MAX_RETRIES) throw err;
        // 指数退避：500ms → 1s → 2s
        await new Promise<void>((ok) => setTimeout(ok, 500 * (1 << tries)));
      }
    }
  };

  return { start, cancel: () => abortCtl.abort() };
}
