"use client";

import { useRouter } from "next/navigation";
import type { UserInfo, UserRoleView } from "@/lib/types";
import {
  ALL_PERMISSIONS,
  PERMISSION_LABELS,
  type Permission,
} from "@/lib/permissions";

export default function Nav({
  user,
  roles,
  permissions,
  onLogout,
}: {
  user: UserInfo | null;
  roles: UserRoleView[];
  permissions: Record<Permission, boolean> | null;
  onLogout: () => void;
}) {
  const router = useRouter();
  const allDenied =
    permissions !== null && ALL_PERMISSIONS.every((p) => !permissions[p]);

  return (
    <div className="topbar">
      <div className="brand">
        <span className="dot" />
        spring-rbac 控制台
      </div>
      <div className="who">
        <div>
          当前用户：<b>{user?.username ?? "—"}</b>{" "}
          {roles.length > 0 && (
            <span className="role-badges">
              {roles.map((r) => (
                <span key={r.roleId} className="badge">
                  {r.roleName}
                </span>
              ))}
            </span>
          )}
          <button
            className="link"
            style={{ marginLeft: 12 }}
            onClick={() => {
              onLogout();
              router.replace("/login");
            }}
          >
            退出
          </button>
        </div>

        {permissions === null ? (
          <div className="perm-row">
            <span className="meta">权限加载中…</span>
          </div>
        ) : (
          <div className="perm-row">
            {ALL_PERMISSIONS.map((p) => (
              <span
                key={p}
                className={`perm-chip ${permissions[p] ? "on" : "off"}`}
                title={p}
              >
                {PERMISSION_LABELS[p]}
              </span>
            ))}
          </div>
        )}

        {allDenied && (
          <div className="noperm-note">
            该账号未分配任何角色，无任何操作权限。可用 admin 在「用户」页注册并授予角色来体验。
          </div>
        )}
      </div>
    </div>
  );
}
