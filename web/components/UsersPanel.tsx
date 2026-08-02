"use client";

import { useEffect, useState, type FormEvent } from "react";
import {
  ApiError,
  assignRoleToUser,
  getUserRoles,
  listRoles,
  register,
  revokeRoleFromUser,
} from "@/lib/api";
import type { Permission } from "@/lib/permissions";
import type { RoleDto, UserRoleView } from "@/lib/types";

export default function UsersPanel({
  token,
  can,
}: {
  token: string;
  can: (p: Permission) => boolean;
}) {
  const canRead = can("users:read");
  const canWrite = can("users:write");

  const [username, setUsername] = useState("alice");
  const [roles, setRoles] = useState<UserRoleView[]>([]);
  const [allRoles, setAllRoles] = useState<RoleDto[]>([]);
  const [pick, setPick] = useState<string>("");
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  // 有写权限时预载角色列表（授予 / 建演示账号都用到）。
  useEffect(() => {
    if (!canWrite) return;
    listRoles(token).then(setAllRoles).catch(() => {});
  }, [token, canWrite]);

  async function query(e: FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    if (!username.trim()) return;
    try {
      const [ur, ar] = await Promise.all([
        getUserRoles(token, username.trim()),
        listRoles(token),
      ]);
      setRoles(ur);
      setAllRoles(ar);
    } catch (e) {
      setErr(e instanceof ApiError ? `查询失败：${e.message}` : "查询失败");
    }
  }

  async function assign() {
    if (!username.trim() || !pick) return;
    setErr("");
    setMsg("");
    try {
      await assignRoleToUser(token, username.trim(), Number(pick));
      setMsg(`已将角色授予 ${username.trim()}`);
      setPick("");
      setRoles(await getUserRoles(token, username.trim()));
    } catch (e) {
      setErr(e instanceof ApiError ? `授权失败：${e.message}` : "授权失败");
    }
  }

  async function revoke(roleId: number, roleName: string | null) {
    if (!username.trim()) return;
    setErr("");
    setMsg("");
    try {
      await revokeRoleFromUser(token, username.trim(), roleId);
      setMsg(`已移除角色 ${roleName ?? roleId}（${username.trim()}）`);
      setRoles(await getUserRoles(token, username.trim()));
    } catch (e) {
      setErr(e instanceof ApiError ? `移除失败：${e.message}` : "移除失败");
    }
  }

  // ---- 建演示账号（注册 + 授予角色，纯前端两步）----
  const [dName, setDName] = useState("");
  const [dPass, setDPass] = useState("");
  const [dRole, setDRole] = useState<string>("");
  const [dBusy, setDBusy] = useState(false);

  async function createDemo(e: FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    if (!dName.trim() || dPass.length < 6 || !dRole) return;
    setDBusy(true);
    try {
      await register(dName.trim(), dPass);
      await assignRoleToUser(token, dName.trim(), Number(dRole));
      setMsg(`已创建演示账号「${dName.trim()}」并授予角色`);
      setDName("");
      setDPass("");
      setDRole("");
    } catch (e) {
      setErr(e instanceof ApiError ? `创建失败：${e.message}` : "创建失败");
    } finally {
      setDBusy(false);
    }
  }

  if (!canRead) {
    return (
      <div className="card">
        <h2>查询用户角色</h2>
        <div className="noperm">无权限查看用户（需要 users:read）。</div>
      </div>
    );
  }

  return (
    <div>
      <div className="card">
        <h2>查询用户角色</h2>
        <p className="sub">输入用户名，查看其直接拥有的角色（GET /api/users/{"{username}"}/roles）。</p>
        {err && <div className="err">{err}</div>}
        {msg && <div className="ok">{msg}</div>}
        <form onSubmit={query}>
          <div className="row">
            <div>
              <label>用户名</label>
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="如 alice"
              />
            </div>
            <div style={{ display: "flex", alignItems: "flex-end" }}>
              <button className="btn" onClick={query} type="submit">
                查询
              </button>
            </div>
          </div>
        </form>

        <ul className="list" style={{ marginTop: 12 }}>
          {roles.map((r) => (
            <li key={r.roleId}>
              <span>
                <span className="name">{r.roleName}</span>{" "}
                <span className="badge">roleId={r.roleId}</span>
              </span>
              {canWrite && (
                <button
                  className="btn btn-danger"
                  onClick={() => revoke(r.roleId, r.roleName)}
                >
                  移除
                </button>
              )}
            </li>
          ))}
          {!err && roles.length === 0 && (
            <li>
              <span className="meta">该用户暂无直接角色</span>
            </li>
          )}
        </ul>
        {!canWrite && (
          <div className="noperm">当前账号无 users:write，不能移除角色（只读）。</div>
        )}
      </div>

      {canWrite && (
        <div className="card">
          <h2>授予用户角色</h2>
          <p className="sub">
            为上方用户名分配一个角色（POST /api/users/{"{username}"}/roles）。结合「鉴权判定」标签可验证权限生效。
          </p>
          <div className="row">
            <div>
              <label>选择角色</label>
              <select value={pick} onChange={(e) => setPick(e.target.value)}>
                <option value="">选择角色…</option>
                {allRoles.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </select>
            </div>
            <div style={{ display: "flex", alignItems: "flex-end" }}>
              <button className="btn" disabled={!pick} onClick={assign}>
                授予
              </button>
            </div>
          </div>
        </div>
      )}

      {canWrite && (
        <div className="card">
          <h2>创建演示账号（注册并授予角色）</h2>
          <p className="sub">
            一键生成「只注册 + 授角色」的账号，用来体验权限感知：例如授予 user / viewer
            角色后，用该账号登录即可看到只读界面（编辑按钮自动消失）。
          </p>
          <form onSubmit={createDemo}>
            <div className="row">
              <div>
                <label>用户名</label>
                <input
                  value={dName}
                  onChange={(e) => setDName(e.target.value)}
                  placeholder="如 reader1"
                />
              </div>
              <div>
                <label>密码（≥6 位）</label>
                <input
                  type="password"
                  value={dPass}
                  onChange={(e) => setDPass(e.target.value)}
                />
              </div>
              <div>
                <label>授予角色</label>
                <select value={dRole} onChange={(e) => setDRole(e.target.value)}>
                  <option value="">选择角色…</option>
                  {allRoles.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div style={{ marginTop: 12 }}>
              <button className="btn" disabled={dBusy || !dName || dPass.length < 6 || !dRole}>
                {dBusy ? "创建中…" : "创建演示账号"}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
