export interface AuthState {
  username: string;
  token: string;
  isAdmin: boolean;
}

// localStorage 键名 — 采用项目前缀避免与其他应用冲突
const STORAGE_KEYS = {
  user: "counselor_user",
  token: "counselor_token",
  role: "counselor_role"
} as const;

/**
 * 从 localStorage 恢复登录凭据。
 * 若 username 或 token 缺失则视为未登录，返回 null。
 */
export function loadAuth(): AuthState | null {
  try {
    const username = localStorage.getItem(STORAGE_KEYS.user);
    const token = localStorage.getItem(STORAGE_KEYS.token);
    if (!username || !token) return null;
    const isAdmin = localStorage.getItem(STORAGE_KEYS.role) === "admin";
    return { username, token, isAdmin };
  } catch {
    return null;
  }
}

/** 将登录凭据持久化到 localStorage */
export function saveAuth(state: AuthState): void {
  localStorage.setItem(STORAGE_KEYS.user, state.username);
  localStorage.setItem(STORAGE_KEYS.token, state.token);
  localStorage.setItem(STORAGE_KEYS.role, state.isAdmin ? "admin" : "user");
}

/** 清除所有登录凭据 */
export function clearAuth(): void {
  Object.values(STORAGE_KEYS).forEach((k) => localStorage.removeItem(k));
}
