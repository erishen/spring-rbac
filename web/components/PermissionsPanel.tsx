"use client";

import { useEffect, useState } from "react";
import { ApiError, listPermissions } from "@/lib/api";
import type { Permission } from "@/lib/permissions";
import type { PermissionDto } from "@/lib/types";

export default function PermissionsPanel({
  token,
  can,
}: {
  token: string;
  can: (p: Permission) => boolean;
}) {
  const [perms, setPerms] = useState<PermissionDto[]>([]);
  const [err, setErr] = useState("");

  useEffect(() => {
    if (!can("permissions:read")) return;
    listPermissions(token)
      .then(setPerms)
      .catch((e) =>
        setErr(e instanceof ApiError ? `加载失败：${e.message}` : "加载失败"),
      );
  }, [token, can]);

  if (!can("permissions:read")) {
    return (
      <div className="card">
        <h2>权限清单</h2>
        <div className="noperm">无权限查看权限（需要 permissions:read）。</div>
      </div>
    );
  }

  return (
    <div className="card">
      <h2>权限清单</h2>
      <p className="sub">
        系统中的所有权限点（种子：users:read / users:write / roles:read / roles:write /
        permissions:read）。权限通过角色间接授予用户。
      </p>
      {err && <div className="err">{err}</div>}
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
        {perms.map((p) => (
          <span key={p.id} className="badge">
            {p.name}
          </span>
        ))}
        {!err && perms.length === 0 && <span className="meta">暂无数据</span>}
      </div>
    </div>
  );
}
