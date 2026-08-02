"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { login as loginApi, me, register as registerApi, getUserRoles } from "./api";
import { fetchEffectivePermissions, type Permission } from "./permissions";
import type { UserInfo, UserRoleView } from "./types";

interface AuthState {
  token: string | null;
  user: UserInfo | null;
  /** 当前用户直接拥有的角色（仅展示用）。 */
  roles: UserRoleView[];
  /** 当前用户的有效权限（含继承），由后端 /api/check 实时裁定。 */
  permissions: Record<Permission, boolean> | null;
  ready: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, password: string) => Promise<void>;
  logout: () => void;
  /** 判断当前用户是否拥有某权限（权限未加载完成前保守返回 false）。 */
  can: (perm: Permission) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);
const TOKEN_KEY = "rbac_token";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<UserInfo | null>(null);
  const [roles, setRoles] = useState<UserRoleView[]>([]);
  const [permissions, setPermissions] =
    useState<Record<Permission, boolean> | null>(null);
  const [ready, setReady] = useState(false);

  // 载入一次完整会话：身份 + 直接角色 + 有效权限。
  const loadSession = useCallback(async (t: string) => {
    const u = await me(t);
    let rs: UserRoleView[] = [];
    try {
      rs = await getUserRoles(t, u.username);
    } catch {
      rs = []; // 无 users:read 时取不到，忽略
    }
    const perms = await fetchEffectivePermissions(t, u.username);
    setUser(u);
    setRoles(rs);
    setPermissions(perms);
  }, []);

  // 启动时从 localStorage 恢复会话并校验 token 是否仍有效。
  useEffect(() => {
    const t = localStorage.getItem(TOKEN_KEY);
    if (!t) {
      setReady(true);
      return;
    }
    setToken(t);
    loadSession(t)
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        setToken(null);
      })
      .finally(() => setReady(true));
  }, [loadSession]);

  const login = useCallback(
    async (username: string, password: string) => {
      const r = await loginApi(username, password);
      localStorage.setItem(TOKEN_KEY, r.token);
      setToken(r.token);
      await loadSession(r.token);
    },
    [loadSession],
  );

  const register = useCallback(
    async (username: string, password: string) => {
      await registerApi(username, password);
      await login(username, password);
    },
    [login],
  );

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setToken(null);
    setUser(null);
    setRoles([]);
    setPermissions(null);
  }, []);

  const can = useCallback(
    (perm: Permission) => !!permissions?.[perm],
    [permissions],
  );

  return (
    <AuthContext.Provider
      value={{ token, user, roles, permissions, ready, login, register, logout, can }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth 必须在 AuthProvider 内使用");
  return ctx;
}
