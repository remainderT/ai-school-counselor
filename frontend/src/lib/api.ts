import type { ApiResult } from "../types";
import { loadAuth } from "./auth-storage";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "";

function buildUrl(path: string): string {
  if (!API_BASE) {
    return path;
  }
  return `${API_BASE}${path}`;
}

async function parseResult<T>(response: Response): Promise<T> {
  let payload: ApiResult<T> | null = null;
  try {
    payload = (await response.json()) as ApiResult<T>;
  } catch {
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    throw new Error("响应格式错误");
  }
  if (!response.ok) {
    throw new Error(payload?.message || `HTTP ${response.status}`);
  }
  if (!payload || typeof payload !== "object") {
    throw new Error("响应格式错误");
  }
  if (payload.code !== "0") {
    throw new Error(payload.message || "请求失败");
  }
  return payload.data;
}

export async function apiGet<T>(path: string): Promise<T> {
  const auth = loadAuth();
  const response = await fetch(buildUrl(path), {
    method: "GET",
    headers: auth
      ? {
          username: auth.username,
          token: auth.token
        }
      : undefined
  });
  return parseResult<T>(response);
}

export async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const auth = loadAuth();
  const response = await fetch(buildUrl(path), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(auth
        ? {
            username: auth.username,
            token: auth.token
          }
        : {})
    },
    body: JSON.stringify(body)
  });
  return parseResult<T>(response);
}

export async function apiDelete<T>(path: string): Promise<T> {
  const auth = loadAuth();
  const response = await fetch(buildUrl(path), {
    method: "DELETE",
    headers: auth
      ? {
          username: auth.username,
          token: auth.token
        }
      : undefined
  });
  return parseResult<T>(response);
}

export async function apiPut<T>(path: string, body: unknown): Promise<T> {
  const auth = loadAuth();
  const response = await fetch(buildUrl(path), {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      ...(auth
        ? {
            username: auth.username,
            token: auth.token
          }
        : {})
    },
    body: JSON.stringify(body)
  });
  return parseResult<T>(response);
}

export async function apiPostForm<T>(path: string, form: FormData): Promise<T> {
  const auth = loadAuth();
  const response = await fetch(buildUrl(path), {
    method: "POST",
    headers: auth
      ? {
          username: auth.username,
          token: auth.token
        }
      : undefined,
    body: form
  });
  return parseResult<T>(response);
}

export function apiUrl(path: string): string {
  return buildUrl(path);
}

export function toErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}
