import { check } from "./api";

/** 系统播种的全部权限点（与 RbacService.seedIfEmpty 一一对应）。 */
export const ALL_PERMISSIONS = [
  "users:read",
  "users:write",
  "roles:read",
  "roles:write",
  "permissions:read",
  "customers:read",
  "customers:write",
] as const;

export type Permission = (typeof ALL_PERMISSIONS)[number];

/** 权限点到中文语义的映射，用于 UI 展示。 */
export const PERMISSION_LABELS: Record<Permission, string> = {
  "users:read": "查看用户",
  "users:write": "操作用户",
  "roles:read": "查看角色",
  "roles:write": "操作角色",
  "permissions:read": "查看权限",
  "customers:read": "查看客户",
  "customers:write": "操作客户",
};

/**
 * 实时计算当前用户对 5 个权限点的有效授权（含角色继承）。
 * 逐个调用 PDP 接口 GET /api/check，由后端（而非前端）裁定，
 * 这样 UI 的显隐完全由服务端 RBAC 决策驱动，体现「证据驱动」的鉴权。
 */
export async function fetchEffectivePermissions(
  token: string,
  username: string,
): Promise<Record<Permission, boolean>> {
  const entries = await Promise.all(
    ALL_PERMISSIONS.map(async (p): Promise<[Permission, boolean]> => {
      try {
        const r = await check(token, username, p);
        return [p, r.allowed];
      } catch {
        return [p, false];
      }
    }),
  );
  return Object.fromEntries(entries) as Record<Permission, boolean>;
}
