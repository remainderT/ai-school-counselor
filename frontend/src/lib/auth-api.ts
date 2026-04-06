import type { ApiResult } from "../types";
import type { AuthState } from "./auth-storage";

const BASE = import.meta.env.VITE_API_BASE_URL || "";

function endpoint(path: string): string {
  return BASE ? `${BASE}${path}` : path;
}

function credentialHeaders(user: string, tok: string): HeadersInit {
  return { username: user, token: tok };
}

/** 解包后端统一响应结构，校验业务码后返回 data */
async function extractData<T>(resp: Response): Promise<T> {
  const body = (await resp.json()) as ApiResult<T>;
  if (!resp.ok) {
    throw new Error(body?.message ?? `HTTP ${resp.status}`);
  }
  if (body == null || String(body.code) !== "0") {
    throw new Error(body?.message ?? "请求失败");
  }
  return body.data;
}

export async function login(username: string, password: string): Promise<AuthState> {
  const resp = await fetch(endpoint("/api/rag/user/login"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  const result = await extractData<{ token: string; isAdmin?: number }>(resp);
  if (!result?.token) {
    throw new Error("登录未返回 token");
  }
  return { username, token: result.token, isAdmin: result.isAdmin === 1 };
}

export async function sendRegisterCode(mail: string): Promise<boolean> {
  const qs = new URLSearchParams({ mail }).toString();
  const resp = await fetch(endpoint(`/api/rag/user/send-code?${qs}`), { method: "GET" });
  return extractData<boolean>(resp);
}

export async function register(payload: {
  username: string;
  password: string;
  mail: string;
  code: string;
}): Promise<void> {
  const resp = await fetch(endpoint("/api/rag/user"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  await extractData<void>(resp);
}

export async function checkLogin(username: string, token: string): Promise<boolean> {
  const qs = new URLSearchParams({ username, token }).toString();
  const resp = await fetch(endpoint(`/api/rag/user/check-login?${qs}`), {
    method: "GET",
    headers: credentialHeaders(username, token)
  });
  return extractData<boolean>(resp);
}

export async function logout(username: string, token: string): Promise<void> {
  const qs = new URLSearchParams({ username, token }).toString();
  const resp = await fetch(endpoint(`/api/rag/user/logout?${qs}`), {
    method: "DELETE",
    headers: credentialHeaders(username, token)
  });
  await extractData<void>(resp);
}

export async function getUserInfo(username: string, token: string): Promise<{ username: string; isAdmin: boolean }> {
  const resp = await fetch(endpoint(`/api/rag/user/info/${encodeURIComponent(username)}`), {
    method: "GET",
    headers: credentialHeaders(username, token)
  });
  const info = await extractData<{ username: string; isAdmin?: number }>(resp);
  return { username: info.username, isAdmin: info.isAdmin === 1 };
}
