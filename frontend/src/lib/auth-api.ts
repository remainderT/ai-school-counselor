import type { AuthState } from "./auth-storage";
import { resolveEndpoint, unwrapResponse } from "./api";

function credentialHeaders(user: string, tok: string): HeadersInit {
  return { username: user, token: tok };
}

export async function login(username: string, password: string): Promise<AuthState> {
  const resp = await fetch(resolveEndpoint("/api/rag/user/login"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  const result = await unwrapResponse<{ token: string; isAdmin?: number }>(resp);
  if (!result?.token) {
    throw new Error("登录未返回 token");
  }
  return { username, token: result.token, isAdmin: result.isAdmin === 1 };
}

export async function sendRegisterCode(mail: string): Promise<boolean> {
  const qs = new URLSearchParams({ mail }).toString();
  const resp = await fetch(resolveEndpoint(`/api/rag/user/send-code?${qs}`), { method: "GET" });
  return unwrapResponse<boolean>(resp);
}

export async function register(payload: {
  username: string;
  password: string;
  mail: string;
  code: string;
}): Promise<void> {
  const resp = await fetch(resolveEndpoint("/api/rag/user"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  await unwrapResponse<void>(resp);
}

export async function checkLogin(username: string, token: string): Promise<boolean> {
  const qs = new URLSearchParams({ username, token }).toString();
  const resp = await fetch(resolveEndpoint(`/api/rag/user/check-login?${qs}`), {
    method: "GET",
    headers: credentialHeaders(username, token)
  });
  return unwrapResponse<boolean>(resp);
}

export async function logout(username: string, token: string): Promise<void> {
  const qs = new URLSearchParams({ username, token }).toString();
  const resp = await fetch(resolveEndpoint(`/api/rag/user/logout?${qs}`), {
    method: "DELETE",
    headers: credentialHeaders(username, token)
  });
  await unwrapResponse<void>(resp);
}

export async function getUserInfo(username: string, token: string): Promise<{ username: string; isAdmin: boolean }> {
  const resp = await fetch(resolveEndpoint(`/api/rag/user/info/${encodeURIComponent(username)}`), {
    method: "GET",
    headers: credentialHeaders(username, token)
  });
  const info = await unwrapResponse<{ username: string; isAdmin?: number }>(resp);
  return { username: info.username, isAdmin: info.isAdmin === 1 };
}
