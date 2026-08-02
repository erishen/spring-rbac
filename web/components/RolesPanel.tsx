"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  ApiError,
  assignPermissionToRole,
  createRole,
  listPermissions,
  listRoles,
} from "@/lib/api";
import type { Permission } from "@/lib/permissions";
import type { PermissionDto, RoleDto } from "@/lib/types";

interface TreeRow {
  role: RoleDto;
  depth: number;
}

export default function RolesPanel({
  token,
  can,
}: {
  token: string;
  can: (p: Permission) => boolean;
}) {
  const canRead = can("roles:read");
  const canWrite = can("roles:write");

  const [roles, setRoles] = useState<RoleDto[]>([]);
  const [perms, setPerms] = useState<PermissionDto[]>([]);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  const [name, setName] = useState("");
  const [parentId, setParentId] = useState<string>("");

  const [pick, setPick] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [r, p] = await Promise.all([listRoles(token), listPermissions(token)]);
      setRoles(r);
      setPerms(p);
    } catch (e) {
      setErr(e instanceof ApiError ? `加载失败：${e.message}` : "加载失败");
    }
  }, [token]);

  useEffect(() => {
    if (canRead) load();
  }, [load, canRead]);

  const tree = useMemo<TreeRow[]>(() => {
    const byId = new Map(roles.map((r) => [r.id, r]));
    const childrenOf = new Map<number | null, RoleDto[]>();
    for (const r of roles) {
      const key = r.parentId ?? null;
      if (!childrenOf.has(key)) childrenOf.set(key, []);
      childrenOf.get(key)!.push(r);
    }
    const out: TreeRow[] = [];
    const walk = (parent: number | null, depth: number) => {
      for (const r of childrenOf.get(parent) ?? []) {
        out.push({ role: r, depth });
        walk(r.id, depth + 1);
      }
    };
    walk(null, 0);
    return out;
  }, [roles]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    if (!name.trim()) return;
    setBusy(true);
    try {
      await createRole(token, name.trim(), parentId ? Number(parentId) : null);
      setMsg(`已创建角色「${name.trim()}」`);
      setName("");
      setParentId("");
      await load();
    } catch (e) {
      setErr(e instanceof ApiError ? `创建失败：${e.message}` : "创建失败");
    } finally {
      setBusy(false);
    }
  }

  async function assign(roleId: number, permId: number) {
    setErr("");
    setMsg("");
    try {
      await assignPermissionToRole(token, roleId, permId);
      const p = perms.find((x) => x.id === permId);
      const r = roles.find((x) => x.id === roleId);
      setMsg(`已给角色「${r?.name}」授予权限「${p?.name}」`);
    } catch (e) {
      setErr(e instanceof ApiError ? `授权失败：${e.message}` : "授权失败");
    }
  }

  if (!canRead) {
    return (
      <div className="card">
        <h2>角色（含继承）</h2>
        <div className="noperm">无权限查看角色（需要 roles:read）。</div>
      </div>
    );
  }

  return (
    <div>
      <div className="card">
        <h2>角色树（含继承）</h2>
        <p className="sub">
          种子数据里 viewer 继承 user，user 继承链向上。下游角色自动拥有上游角色的权限。
        </p>
        {err && <div className="err">{err}</div>}
        {msg && <div className="ok">{msg}</div>}
        <ul className="list">
          {tree.map(({ role, depth }) => (
            <li key={role.id} className={depth === 1 ? "indent-1" : depth >= 2 ? "indent-2" : ""}>
              <span>
                <span className="name">{role.name}</span>{" "}
                <span className="badge">id={role.id}</span>
                {role.parentId != null && (
                  <span className="badge inherit">
                    继承 {(roles.find((r) => r.id === role.parentId)?.name) ?? role.parentId}
                  </span>
                )}
              </span>
            </li>
          ))}
        </ul>
      </div>

      {canWrite ? (
        <div className="card">
          <h2>新建角色</h2>
          <p className="sub">可选父角色，建立继承关系。</p>
          <form onSubmit={submit}>
            <div className="row">
              <div>
                <label>角色名</label>
                <input value={name} onChange={(e) => setName(e.target.value)} placeholder="如 auditor" />
              </div>
              <div>
                <label>父角色（可选）</label>
                <select value={parentId} onChange={(e) => setParentId(e.target.value)}>
                  <option value="">（无 / 顶级角色）</option>
                  {roles.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div style={{ marginTop: 12 }}>
              <button className="btn" disabled={busy}>
                {busy ? "创建中…" : "创建角色"}
              </button>
            </div>
          </form>
        </div>
      ) : (
        <div className="card">
          <h2>新建角色</h2>
          <div className="noperm">当前账号无 roles:write，不能创建/修改角色（只读）。</div>
        </div>
      )}

      {canWrite ? (
        <div className="card">
          <h2>给角色授权限</h2>
          <p className="sub">
            选择角色与权限进行绑定（POST /api/roles/{"{id}"}/permissions）。注：当前后端未提供
            “列出某角色已有权限”的接口，这里只做授予操作。
          </p>
          <ul className="list">
            {roles.map((r) => (
              <li key={r.id}>
                <span className="name">{r.name}</span>
                <span style={{ display: "flex", gap: 8, alignItems: "center" }}>
                  <select
                    style={{ width: 200 }}
                    value={pick[r.id] ?? ""}
                    onChange={(e) => setPick((p) => ({ ...p, [r.id]: e.target.value }))}
                  >
                    <option value="">选择权限…</option>
                    {perms.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                  <button
                    className="btn"
                    disabled={!pick[r.id]}
                    onClick={() => assign(r.id, Number(pick[r.id]))}
                  >
                    授权
                  </button>
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <div className="card">
          <h2>给角色授权限</h2>
          <div className="noperm">当前账号无 roles:write，不能给角色授权限（只读）。</div>
        </div>
      )}
    </div>
  );
}
