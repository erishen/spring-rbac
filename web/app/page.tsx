"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import type { Permission } from "@/lib/permissions";
import Nav from "@/components/Nav";
import RolesPanel from "@/components/RolesPanel";
import PermissionsPanel from "@/components/PermissionsPanel";
import UsersPanel from "@/components/UsersPanel";
import CheckPanel from "@/components/CheckPanel";
import CustomersPanel from "@/components/CustomersPanel";
import ApprovalsPanel from "@/components/ApprovalsPanel";
import AuditPanel from "@/components/AuditPanel";

type Tab = "roles" | "permissions" | "users" | "check" | "customers" | "approvals" | "audit";

const TABS: { key: Tab; label: string; readPerm?: Permission }[] = [
  { key: "roles", label: "角色 Roles", readPerm: "roles:read" },
  { key: "permissions", label: "权限 Permissions", readPerm: "permissions:read" },
  { key: "users", label: "用户 Users", readPerm: "users:read" },
  { key: "customers", label: "客户 Customers", readPerm: "customers:read" },
  { key: "approvals", label: "审批 Approvals", readPerm: "customers:approve" },
  { key: "audit", label: "审计 Audit", readPerm: "audit:read" },
  { key: "check", label: "鉴权判定 Check" },
];

export default function Dashboard() {
  const { token, user, roles, permissions, ready, can, logout } = useAuth();
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("roles");

  useEffect(() => {
    if (ready && !token) router.replace("/login");
  }, [ready, token, router]);

  if (!ready) return <div className="center">加载中…</div>;
  if (!token) return null;

  return (
    <div className="app">
      <Nav user={user} roles={roles} permissions={permissions} onLogout={logout} />

      <div className="tabs">
        {TABS.map((t) => {
          const locked = t.readPerm ? !can(t.readPerm) : false;
          return (
            <button
              key={t.key}
              className={tab === t.key ? "active" : ""}
              onClick={() => setTab(t.key)}
            >
              {t.label}
              {locked && <span className="lock" title="无查看权限"> 🔒</span>}
            </button>
          );
        })}
      </div>

      <main>
        {tab === "roles" && <RolesPanel token={token} can={can} />}
        {tab === "permissions" && <PermissionsPanel token={token} can={can} />}
        {tab === "users" && <UsersPanel token={token} can={can} />}
        {tab === "customers" && <CustomersPanel token={token} can={can} />}
        {tab === "approvals" && <ApprovalsPanel token={token} can={can} />}
        {tab === "audit" && <AuditPanel token={token} can={can} />}
        {tab === "check" && <CheckPanel token={token} />}
      </main>
    </div>
  );
}
