import { useEffect, useMemo, useState } from "react";
import { ChatWorkbench } from "./components/ChatWorkbench";
import { SearchPanel } from "./components/SearchPanel";
import { KnowledgePanel } from "./components/KnowledgePanel";
import { DocumentPanel } from "./components/DocumentPanel";
import { IntentTreePanel } from "./components/IntentTreePanel";
import { ToastHost } from "./components/ToastHost";
import { pushToast } from "./lib/toast";
import { useActionRequest } from "./hooks/useActionRequest";
import { useAuthStore } from "./store/auth-store";

type TabKey = "chat" | "search" | "knowledge" | "document" | "intent-tree";

const BRAND_NAME = "BUAA AI 辅导员";

/* ---- Inline SVG Icons ---- */
const AdminIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);

const BackIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 12H5" />
    <polyline points="12 19 5 12 12 5" />
  </svg>
);

const LogoutIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
    <polyline points="16 17 21 12 16 7" />
    <line x1="21" y1="12" x2="9" y2="12" />
  </svg>
);

export default function App() {
  const [tab, setTab] = useState<TabKey>("chat");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [mail, setMail] = useState("");
  const [code, setCode] = useState("");
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [authMsg, setAuthMsg] = useState("");
  const [adminPanelOpen, setAdminPanelOpen] = useState(false);

  const authReq = useActionRequest();
  const { auth, isAdmin, restoring, loginWithPassword, logoutCurrent, registerUser, sendCode } = useAuthStore();

  const adminTabs = useMemo(() => [
    { key: "search" as const, label: "检索调试", icon: "search" },
    { key: "knowledge" as const, label: "知识库管理", icon: "knowledge" },
    { key: "intent-tree" as const, label: "意图树管理", icon: "tree" },
    { key: "document" as const, label: "文档管理", icon: "document" },
  ], []);

  useEffect(() => {
    if (!isAdmin && tab !== "chat") {
      setTab("chat");
      setAdminPanelOpen(false);
    }
  }, [isAdmin, tab]);

  useEffect(() => {
    if (auth?.username) {
      setUsername(auth.username);
    }
  }, [auth?.username]);

  const doLogin = async () => {
    if (!username.trim() || !password.trim()) {
      setAuthMsg("请输入用户名和密码");
      return;
    }
    setAuthMsg("");
    const result = await authReq.runAction(() => loginWithPassword(username.trim(), password), {
      errorFallback: "登录失败",
      onError: setAuthMsg
    });
    if (result.ok) {
      setAuthMsg("登录成功");
      pushToast("登录成功", "success");
      setPassword("");
    }
  };

  const doSendCode = async () => {
    if (!mail.trim()) {
      setAuthMsg("请输入邮箱");
      pushToast("请输入邮箱", "error");
      return;
    }
    const result = await authReq.runAction(() => sendCode(mail.trim()), {
      errorFallback: "验证码发送失败",
      onError: setAuthMsg
    });
    if (result.ok) {
      const ok = result.data;
      const msg = ok ? "验证码已发送" : "验证码发送失败";
      setAuthMsg(msg);
      pushToast(msg, ok ? "success" : "error");
    }
  };

  const doRegister = async () => {
    if (!username.trim() || !password.trim() || !mail.trim() || !code.trim()) {
      setAuthMsg("注册需填写用户名、密码、邮箱和验证码");
      pushToast("注册需填写完整信息", "error");
      return;
    }
    const result = await authReq.runAction(
      () => registerUser({ username: username.trim(), password, mail: mail.trim(), code: code.trim() }),
      {
        errorFallback: "注册失败",
        onError: setAuthMsg
      }
    );
    if (result.ok) {
      setAuthMsg("注册成功，请登录");
      pushToast("注册成功，请登录", "success");
      setAuthMode("login");
      setCode("");
    }
  };

  const handleAuthKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      e.preventDefault();
      if (authMode === "login") {
        void doLogin();
      }
    }
  };

  const handleAdminTabClick = (key: TabKey) => {
    setTab(key);
  };

  const handleBackToChat = () => {
    setTab("chat");
    setAdminPanelOpen(false);
  };

  if (restoring) {
    return (
      <div className="auth-screen">
        <div className="auth-panel" style={{ textAlign: "center", padding: "60px 36px" }}>
          <div style={{ fontSize: 14, color: "#999" }}>恢复登录态中...</div>
        </div>
      </div>
    );
  }

  if (!auth) {
    return (
      <>
        <div className="auth-screen">
          <section className="auth-panel" onKeyDown={handleAuthKeyDown}>
            <div className="auth-hero">
              <div className="brand auth-brand">
                <div className="brand-logo">✦</div>
                <div>
                  <div className="brand-title">{BRAND_NAME}</div>
                  <div className="brand-subtitle">北京航空航天大学</div>
                </div>
              </div>
            </div>

            <div className="auth-form">
              <div className="auth-form-head">
                <h2 className="auth-title">{authMode === "login" ? "欢迎回来" : "创建账号"}</h2>
                <p className="auth-form-tip">{authMode === "login" ? "登录以开始你的智能学业辅导" : "注册账号，开启 AI 辅导之旅"}</p>
              </div>

              <div className="auth-mode-switch" role="tablist" aria-label="登录与注册">
                <button className={authMode === "login" ? "auth-mode-btn active" : "auth-mode-btn"} onClick={() => setAuthMode("login")}>登录</button>
                <button className={authMode === "register" ? "auth-mode-btn active" : "auth-mode-btn"} onClick={() => setAuthMode("register")}>注册</button>
              </div>

              <div className="auth-fields">
                <label className="auth-field">
                  用户名
                  <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="请输入用户名" autoComplete="username" />
                </label>
                <label className="auth-field">
                  密码
                  <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="请输入密码" autoComplete="current-password" />
                </label>
                {authMode === "register" && (
                  <label className="auth-field">
                    邮箱
                    <input value={mail} onChange={(e) => setMail(e.target.value)} placeholder="请输入邮箱" />
                  </label>
                )}
                {authMode === "register" && (
                  <label className="auth-field">
                    验证码
                    <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="请输入验证码" />
                  </label>
                )}
              </div>

              <div className="auth-actions">
                {authMode === "login" ? (
                  <button className="auth-main-btn" onClick={doLogin} disabled={authReq.loading}>
                    {authReq.loading ? "登录中..." : "登录"}
                  </button>
                ) : (
                  <>
                    <button className="auth-sub-btn" onClick={doSendCode} disabled={authReq.loading}>发送验证码</button>
                    <button className="auth-main-btn" onClick={doRegister} disabled={authReq.loading}>
                      {authReq.loading ? "注册中..." : "注册"}
                    </button>
                  </>
                )}
              </div>

              {authMsg && (
                <p className={`auth-msg ${authMsg.includes("成功") ? "ok" : "error"}`}>{authMsg}</p>
              )}

              <div className="auth-help-row">
                <span>© 2026 北京航空航天大学 · AI 学业辅导系统</span>
              </div>
            </div>
          </section>
        </div>
        <ToastHost />
      </>
    );
  }

  /* ---- Admin panel is open: show admin sidebar + admin content ---- */
  if (isAdmin && adminPanelOpen) {
    return (
      <div className="console-layout">
        <aside className="sidebar">
          <div className="brand">
            <div className="brand-logo">✦</div>
            <div className="brand-title">{BRAND_NAME}</div>
          </div>

          <button className="admin-back-btn" onClick={handleBackToChat}>
            <BackIcon />
            <span>返回聊天</span>
          </button>

          <nav className="side-nav" aria-label="管理功能">
            {adminTabs.map((item) => (
              <button
                key={item.key}
                className={tab === item.key ? "side-item active" : "side-item"}
                onClick={() => handleAdminTabClick(item.key)}
              >
                <span className={`side-icon side-icon-${item.icon}`} aria-hidden="true" />
                <span>{item.label}</span>
              </button>
            ))}
          </nav>

          <div className="sidebar-footer">
            <div className="sidebar-user">
              <div className="sidebar-user-avatar">{auth.username.charAt(0).toUpperCase()}</div>
              <span className="sidebar-user-name">{auth.username}</span>
            </div>
            <button className="sidebar-logout-btn" onClick={logoutCurrent} title="退出登录">
              <LogoutIcon />
            </button>
          </div>
        </aside>

        <div className="workspace">
          <main className="panel-wrap">
            {tab === "search" && <SearchPanel />}
            {tab === "knowledge" && <KnowledgePanel />}
            {tab === "document" && <DocumentPanel />}
            {tab === "intent-tree" && <IntentTreePanel />}
          </main>
        </div>
        <ToastHost />
      </div>
    );
  }

  /* ---- Normal view: no left sidebar, full-width chat ---- */
  const adminEntryButton = isAdmin ? (
    <button
      className="admin-entry-btn"
      onClick={() => {
        setAdminPanelOpen(true);
        if (tab === "chat") setTab("search");
      }}
      title="管理后台"
    >
      <AdminIcon />
      <span>管理后台</span>
    </button>
  ) : null;

  return (
    <div className="console-layout console-layout-full">
      <div className="workspace">
        <main className="panel-wrap">
          <ChatWorkbench authUsername={auth.username} adminEntryButton={adminEntryButton} onLogout={logoutCurrent} />
        </main>
      </div>
      <ToastHost />
    </div>
  );
}
