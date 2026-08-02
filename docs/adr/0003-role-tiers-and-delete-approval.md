# ADR-0003：权限模型——三档角色 + 删除审批 + admin 直删

- 状态：已接受
- 日期：2026-08-01 ~ 2026-08-02

## 背景

CRM 应用最初只有笼统的 `customers:write`，所有能写的人权限一致，角色差异没有体现。删除客户是敏感操作，需要留痕与把关；但 admin 自己删除还要走一遍审批又过于迂回。需求演进分三小步：拆细写权限 → 加删除审批流 → admin 直删。

## 决策

1. **拆分写权限**：`customers:write` → `customers:read / create / update / delete`，网关按 HTTP 方法映射（GET→read、POST→create、PUT→update、DELETE→delete）。
2. **三档角色**：
   - `admin`：全权 + `customers:approve`（可批删、可直删）；
   - `editor`：可增改删，**无**审批权（删除只能申请）；
   - `viewer`：纯只读。
3. **删除审批流**：`DELETE /api/customers/{id}` 时，customer-service 读 `X-User` 判 actor 是否有 `customers:approve`：
   - 有 → 物理删除，返回 `200 {deleted:true}`；
   - 无 → 生成 `approval_requests` 待审批单（PENDING，幂等：同一客户未决单不重复建），返回 `202 {deleted:false, approvalId}`。
4. **审批闭环**：admin 在「审批 Approvals」页对 PENDING 单通过/驳回（`POST /api/approvals/{id}/approve|reject`，需 `customers:approve`），通过后由 service 执行真删。

## 后果

- 角色差异落到界面与 API 两层：按钮显隐（编辑/删除/申请删除）、后端权限双保险。
- 删除有两条留痕路径：审批流（审批单）+ 统一审计（ADR-0004，覆盖 admin 直删）。
- RBAC 种子每次启动重置（见 ADR-0005），三档行为可复现。
