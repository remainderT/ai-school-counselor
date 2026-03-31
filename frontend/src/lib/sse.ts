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

function parseData(raw: string): unknown {
  if (!raw) {
    return "";
  }
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

export function createChatStream(message: string, userId: string, handlers: StreamHandlers) {
  const controller = new AbortController();
  const query = new URLSearchParams({ message, userId }).toString();
  const url = apiUrl(`/api/rag/chat/stream?${query}`);

  const start = async () => {
    const auth = loadAuth();
    const response = await fetch(url, {
      method: "GET",
      headers: {
        Accept: "text/event-stream",
        ...(auth
          ? {
              username: auth.username,
              token: auth.token
            }
          : {})
      },
      signal: controller.signal
    });
    if (!response.ok || !response.body) {
      throw new Error(`流式请求失败 (${response.status})`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    let eventName = "message";
    let dataLines: string[] = [];

    const flushEvent = () => {
      if (dataLines.length === 0) {
        eventName = "message";
        return;
      }
      const raw = dataLines.join("\n");
      const data = parseData(raw);
      handlers.onEvent?.({ event: eventName, data });

      if (eventName === "message") {
        handlers.onText?.(String(data));
      } else if (eventName === "sources") {
        handlers.onSources?.(data);
      } else if (eventName === "messageId") {
        handlers.onMessageId?.(Number(data));
      } else if (eventName === "done") {
        handlers.onDone?.();
      } else if (eventName === "error") {
        handlers.onError?.(new Error(String(data)));
      }

      eventName = "message";
      dataLines = [];
    };

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        flushEvent();
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        if (!line) {
          flushEvent();
          continue;
        }
        if (line.startsWith("event:")) {
          eventName = line.slice(6).trim();
          continue;
        }
        if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trim());
        }
      }
    }
  };

  return {
    start,
    cancel: () => controller.abort()
  };
}
