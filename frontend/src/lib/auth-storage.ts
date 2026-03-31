export interface AuthState {
  username: string;
  token: string;
  isAdmin: boolean;
}

const KEY_USERNAME = "rag_auth_username";
const KEY_TOKEN = "rag_auth_token";
const KEY_IS_ADMIN = "rag_auth_is_admin";

export function loadAuth(): AuthState | null {
  const username = localStorage.getItem(KEY_USERNAME);
  const token = localStorage.getItem(KEY_TOKEN);
  if (!username || !token) {
    return null;
  }
  const isAdmin = localStorage.getItem(KEY_IS_ADMIN) === "1";
  return { username, token, isAdmin };
}

export function saveAuth(state: AuthState): void {
  localStorage.setItem(KEY_USERNAME, state.username);
  localStorage.setItem(KEY_TOKEN, state.token);
  localStorage.setItem(KEY_IS_ADMIN, state.isAdmin ? "1" : "0");
}

export function clearAuth(): void {
  localStorage.removeItem(KEY_USERNAME);
  localStorage.removeItem(KEY_TOKEN);
  localStorage.removeItem(KEY_IS_ADMIN);
}
