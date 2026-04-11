import type { ApiResult } from "../types";
import { loadAuth } from "./auth-storage";

const BASE = import.meta.env.VITE_API_BASE_URL || "";

/** 拼接 API 基础路径与请求路径 */
export function resolveEndpoint(path: string): string {
  return BASE ? `${BASE}${path}` : path;
}

/** 构造包含认证信息的请求头（若已登录） */
function authHeaders(): Record<string, string> | undefined {
  const auth = loadAuth();
  return auth ? { username: auth.username, token: auth.token } : undefined;
}

/** 从 fetch Response 中解包统一结果并提取 data 字段 */
export async function unwrapResponse<T>(resp: Response): Promise<T> {
  let envelope: ApiResult<T> | null = null;
  try {
    envelope = (await resp.json()) as ApiResult<T>;
  } catch {
    throw new Error(resp.ok ? "响应格式错误" : `HTTP ${resp.status}`);
  }
  if (!resp.ok) {
    throw new Error(envelope?.message ?? `HTTP ${resp.status}`);
  }
  if (envelope == null || typeof envelope !== "object") {
    throw new Error("响应格式错误");
  }
  if (String(envelope.code) !== "0") {
    throw new Error(envelope.message || "请求失败");
  }
  return envelope.data;
}

export async function apiGet<T>(path: string): Promise<T> {
  const resp = await fetch(resolveEndpoint(path), {
    method: "GET",
    headers: authHeaders()
  });
  return unwrapResponse<T>(resp);
}

export async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const hdrs = authHeaders() ?? {};
  const resp = await fetch(resolveEndpoint(path), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...hdrs },
    body: JSON.stringify(body)
  });
  return unwrapResponse<T>(resp);
}

export async function apiDelete<T>(path: string): Promise<T> {
  const resp = await fetch(resolveEndpoint(path), {
    method: "DELETE",
    headers: authHeaders()
  });
  return unwrapResponse<T>(resp);
}

export async function apiPut<T>(path: string, body: unknown): Promise<T> {
  const hdrs = authHeaders() ?? {};
  const resp = await fetch(resolveEndpoint(path), {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...hdrs },
    body: JSON.stringify(body)
  });
  return unwrapResponse<T>(resp);
}

export async function apiPostForm<T>(path: string, form: FormData): Promise<T> {
  const resp = await fetch(resolveEndpoint(path), {
    method: "POST",
    headers: authHeaders(),
    body: form
  });
  return unwrapResponse<T>(resp);
}

export function apiUrl(path: string): string {
  return resolveEndpoint(path);
}

export function toErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}
