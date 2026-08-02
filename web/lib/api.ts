import type {
  CheckResponse,
  CreateCustomerRequest,
  CustomerDto,
  CustomerPage,
  PermissionDto,
  RoleDto,
  TokenResponse,
  UpdateCustomerRequest,
  UserInfo,
  UserRoleView,
} from "./types";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = "ApiError";
  }
}

interface ApiOptions {
  method?: string;
  body?: unknown;
  token?: string | null;
}

/**
 * 统一请求封装。
 * 浏览器只请求同源 /api/*，由 Next 服务端 rewrite 代理到 Spring Cloud Gateway。
 * 鉴权：存在 token 时自动带 Bearer；后端返回非 2xx 时抛 ApiError（含状态码与消息）。
 */
export async function api<T = unknown>(
  path: string,
  opts: ApiOptions = {},
): Promise<T> {
  const res = await fetch(path, {
    method: opts.method ?? "GET",
    headers: {
      "Content-Type": "application/json",
      ...(opts.token ? { Authorization: `Bearer ${opts.token}` } : {}),
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });

  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;

  if (!res.ok) {
    const msg =
      (data && (data.message || data.error)) ||
      (data && typeof data === "object" ? JSON.stringify(data) : "") ||
      res.statusText;
    throw new ApiError(res.status, msg || `HTTP ${res.status}`);
  }
  return data as T;
}

// ---- 认证 ----
export const login = (username: string, password: string) =>
  api<TokenResponse>("/api/login", { method: "POST", body: { username, password } });

export const register = (username: string, password: string) =>
  api<{ id: number; username: string }>("/api/register", {
    method: "POST",
    body: { username, password },
  });

export const me = (token: string) => api<UserInfo>("/api/me", { token });

// ---- RBAC ----
export const listRoles = (token: string) => api<RoleDto[]>("/api/roles", { token });
export const createRole = (token: string, name: string, parentId: number | null) =>
  api<RoleDto>("/api/roles", { method: "POST", token, body: { name, parentId } });
export const listPermissions = (token: string) =>
  api<PermissionDto[]>("/api/permissions", { token });
export const getUserRoles = (token: string, username: string) =>
  api<UserRoleView[]>(`/api/users/${encodeURIComponent(username)}/roles`, { token });
export const assignRoleToUser = (token: string, username: string, roleId: number) =>
  api<{ username: string; roleId: number }>(
    `/api/users/${encodeURIComponent(username)}/roles`,
    { method: "POST", token, body: { roleId } },
  );
export const revokeRoleFromUser = (token: string, username: string, roleId: number) =>
  api<void>(
    `/api/users/${encodeURIComponent(username)}/roles/${roleId}`,
    { method: "DELETE", token },
  );
export const assignPermissionToRole = (token: string, roleId: number, permissionId: number) =>
  api<{ roleId: number; permissionId: number }>(
    `/api/roles/${roleId}/permissions`,
    { method: "POST", token, body: { permissionId } },
  );
export const check = (token: string, user: string, permission: string) =>
  api<CheckResponse>(
    `/api/check?user=${encodeURIComponent(user)}&permission=${encodeURIComponent(permission)}`,
    { token },
  );

// ---- CRM 客户（鉴权委托给网关 PEP + RBAC PDP）----
export const listCustomers = (token: string, q?: string, page = 0, size = 20) =>
  api<CustomerPage>(
    `/api/customers?page=${page}&size=${size}${q ? `&q=${encodeURIComponent(q)}` : ""}`,
    { token },
  );
export const getCustomer = (token: string, id: number) =>
  api<CustomerDto>(`/api/customers/${id}`, { token });
export const createCustomer = (token: string, body: CreateCustomerRequest) =>
  api<CustomerDto>("/api/customers", { method: "POST", token, body });
export const updateCustomer = (token: string, id: number, body: UpdateCustomerRequest) =>
  api<CustomerDto>(`/api/customers/${id}`, { method: "PUT", token, body });
export const deleteCustomer = (token: string, id: number) =>
  api<void>(`/api/customers/${id}`, { method: "DELETE", token });
