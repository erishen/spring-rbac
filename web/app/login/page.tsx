"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

export default function LoginPage() {
  const { login, register, token, ready } = useAuth();
  const router = useRouter();

  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("admin123");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (ready && token) router.replace("/");
  }, [ready, token, router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr("");
    setBusy(true);
    try {
      if (mode === "login") await login(username, password);
      else await register(username, password);
      router.replace("/");
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "操作失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-wrap">
      <div className="card">
        <h2>spring-rbac 控制台</h2>
        <p className="sub">登录以管理角色、权限与用户鉴权</p>

        <div className="auth-tabs">
          <button
            className={mode === "login" ? "on" : ""}
            onClick={() => setMode("login")}
          >
            登录
          </button>
          <button
            className={mode === "register" ? "on" : ""}
            onClick={() => setMode("register")}
          >
            注册
          </button>
        </div>

        <form onSubmit={submit}>
          <div className="field">
            <label>用户名</label>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
            />
          </div>
          <div className="field">
            <label>密码（至少 6 位）</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === "login" ? "current-password" : "new-password"}
            />
          </div>

          {err && <div className="err">{err}</div>}

          <button className="btn" style={{ width: "100%" }} disabled={busy}>
            {busy ? "处理中…" : mode === "login" ? "登录" : "注册并登录"}
          </button>
        </form>

        <div className="hint" style={{ marginTop: 14, marginBottom: 0 }}>
          默认管理员：<b>admin / admin123</b>（拥有全部权限）。在「用户」页用
          「创建演示账号」注册并授予 <b>user</b> / <b>viewer</b> 角色，再以该账号登录，
          即可看到按钮随权限自动消失（权限感知 UI）。
        </div>
      </div>
    </div>
  );
}
