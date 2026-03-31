import type { ApiResult } from "../types";
import type { AuthState } from "./auth-storage";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "";

function url(path: string): string {
  return API_BASE ? `${API_BASE}${path}` : path;
}

function authHeaders(username: string, token: string): HeadersInit {
  return {
    username,
    token
  };
}

async function parseResult<T>(response: Response): Promise<T> {
  const payload = (await response.json()) as ApiResult<T>;
  if (!response.ok) {
    throw new Error(payload?.message || `HTTP ${response.status}`);
  }
  if (!payload || payload.code !== "0") {
    throw new Error(payload?.message || "请求失败");
  }
  return payload.data;
}

export async function login(username: string, password: string): Promise<AuthState> {
  const response = await fetch(url("/api/rag/user/login"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  const data = await parseResult<{ token: string; isAdmin?: number }>(response);
  if (!data?.token) {
    throw new Error("登录未返回 token");
  }
  return { username, token: data.token, isAdmin: data.isAdmin === 1 };
}

export async function sendRegisterCode(mail: string): Promise<boolean> {
  const query = new URLSearchParams({ mail }).toString();
  const response = await fetch(url(`/api/rag/user/send-code?${query}`), { method: "GET" });
  return parseResult<boolean>(response);
}

export async function register(payload: {
  username: string;
  password: string;
  mail: string;
  code: string;
}): Promise<void> {
  const response = await fetch(url("/api/rag/user"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  await parseResult<void>(response);
}

export async function checkLogin(username: string, token: string): Promise<boolean> {
  const query = new URLSearchParams({ username, token }).toString();
  const response = await fetch(url(`/api/rag/user/check-login?${query}`), {
    method: "GET",
    headers: authHeaders(username, token)
  });
  return parseResult<boolean>(response);
}

export async function logout(username: string, token: string): Promise<void> {
  const query = new URLSearchParams({ username, token }).toString();
  const response = await fetch(url(`/api/rag/user/logout?${query}`), {
    method: "DELETE",
    headers: authHeaders(username, token)
  });
  await parseResult<void>(response);
}

export async function getUserInfo(username: string, token: string): Promise<{ username: string; isAdmin: boolean }> {
  const response = await fetch(url(`/api/rag/user/info/${encodeURIComponent(username)}`), {
    method: "GET",
    headers: authHeaders(username, token)
  });
  const data = await parseResult<{ username: string; isAdmin?: number }>(response);
  return { username: data.username, isAdmin: data.isAdmin === 1 };
}
