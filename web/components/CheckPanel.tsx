"use client";

import { useEffect, useState } from "react";
import { ApiError, check, listPermissions } from "@/lib/api";
import type { PermissionDto } from "@/lib/types";

export default function CheckPanel({ token }: { token: string }) {
  const [user, setUser] = useState("alice");
  const [perm, setPerm] = useState("roles:read");
  const [perms, setPerms] = useState<PermissionDto[]>([]);
  const [verdict, setVerdict] = useState<boolean | null>(null);
  const [detail, setDetail] = useState("");
  const [err, setErr] = useState("");

  useEffect(() => {
    listPermissions(token)
      .then((p) => {
        setPerms(p);
        if (p.length && !p.some((x) => x.name === perm)) setPerm(p[0].name);
      })
      .catch(() => {});
  }, [token, perm]);

  async function run() {
    setErr("");
    setVerdict(null);
    setDetail("");
    if (!user.trim() || !perm) return;
    try {
      const r = await check(token, user.trim(), perm);
      setVerdict(r.allowed);
      setDetail(
        r.allowed
          ? `「${user.trim()}」经角色继承后拥有「${perm}」`
          : `「${user.trim()}」未被授予「${perm}」（含继承链）`,
      );
    } catch (e) {
      setErr(e instanceof ApiError ? `判定失败：${e.message}` : "判定失败");
    }
  }

  return (
    <div className="card">
      <h2>鉴权判定（含继承）</h2>
      <p className="sub">
        调用 PDP 接口 GET /api/check?user=&amp;permission=，实时计算用户的有效权限（含角色继承）。
        例如 viewer 角色仅含 customers:read（只读），则 customers:read / roles:read / users:read 应为允许，customers:create/update/delete 应为拒绝。
      </p>
      {err && <div className="err">{err}</div>}

      <div className="row">
        <div>
          <label>用户名</label>
          <input value={user} onChange={(e) => setUser(e.target.value)} placeholder="alice" />
        </div>
        <div>
          <label>权限点</label>
          <select value={perm} onChange={(e) => setPerm(e.target.value)}>
            {perms.map((p) => (
              <option key={p.id} value={p.name}>
                {p.name}
              </option>
            ))}
            {perms.length === 0 && <option value={perm}>{perm}</option>}
          </select>
        </div>
        <div style={{ display: "flex", alignItems: "flex-end" }}>
          <button className="btn" onClick={run}>
            判定
          </button>
        </div>
      </div>

      {verdict !== null && (
        <div className={`verdict ${verdict ? "allow" : "deny"}`}>
          {verdict ? "✓ 允许 ALLOWED" : "✕ 拒绝 DENIED"}
        </div>
      )}
      {detail && <div className="ok" style={{ marginTop: 12, textAlign: "center" }}>{detail}</div>}
    </div>
  );
}
