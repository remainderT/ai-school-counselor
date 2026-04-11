export type ToastTone = "info" | "success" | "error";

export interface ToastItem {
  id: string;
  message: string;
  tone: ToastTone;
  durationMs: number;
}

type ToastListener = (item: ToastItem) => void;

const listeners = new Set<ToastListener>();

function createId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function pushToast(message: string, tone: ToastTone = "info", durationMs?: number) {
  if (durationMs === undefined) {
    durationMs = tone === "success" ? 1000 : 3200;
  }
  const item: ToastItem = { id: createId(), message, tone, durationMs };
  listeners.forEach((listener) => listener(item));
}

export function subscribeToast(listener: ToastListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
