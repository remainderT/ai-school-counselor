import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { checkLogin, getUserInfo, login, logout, register, sendRegisterCode } from "../lib/auth-api";
import { clearAuth, loadAuth, saveAuth, type AuthState } from "../lib/auth-storage";

type AuthStore = {
  auth: AuthState | null;
  isAdmin: boolean;
  restoring: boolean;
  loginWithPassword: (username: string, password: string) => Promise<void>;
  registerUser: (payload: { username: string; password: string; mail: string; code: string }) => Promise<void>;
  sendCode: (mail: string) => Promise<boolean>;
  logoutCurrent: () => Promise<void>;
};

const AuthContext = createContext<AuthStore | null>(null);

async function loadProfile(username: string, token: string): Promise<AuthState | null> {
  const ok = await checkLogin(username, token);
  if (!ok) return null;
  const profile = await getUserInfo(username, token);
  return { username, token, isAdmin: profile.isAdmin };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [auth, setAuth] = useState<AuthState | null>(null);
  const [restoring, setRestoring] = useState(true);

  useEffect(() => {
    void (async () => {
      try {
        const saved = loadAuth();
        if (!saved) {
          setAuth(null);
          return;
        }
        const refreshed = await loadProfile(saved.username, saved.token);
        if (!refreshed) {
          clearAuth();
          setAuth(null);
          return;
        }
        saveAuth(refreshed);
        setAuth(refreshed);
      } catch {
        clearAuth();
        setAuth(null);
      } finally {
        setRestoring(false);
      }
    })();
  }, []);

  const store = useMemo<AuthStore>(() => ({
    auth,
    isAdmin: auth?.isAdmin === true,
    restoring,
    async loginWithPassword(username: string, password: string) {
      const state = await login(username, password);
      const profile = await getUserInfo(state.username, state.token);
      const merged: AuthState = { ...state, isAdmin: profile.isAdmin };
      saveAuth(merged);
      setAuth(merged);
    },
    async registerUser(payload) {
      await register(payload);
    },
    async sendCode(mail: string) {
      return sendRegisterCode(mail);
    },
    async logoutCurrent() {
      if (!auth) return;
      try {
        await logout(auth.username, auth.token);
      } finally {
        clearAuth();
        setAuth(null);
      }
    }
  }), [auth, restoring]);

  return <AuthContext.Provider value={store}>{children}</AuthContext.Provider>;
}

export function useAuthStore() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuthStore must be used within AuthProvider");
  }
  return ctx;
}
