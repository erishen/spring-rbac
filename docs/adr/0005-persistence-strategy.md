# ADR-0005：持久化策略——业务/审计 update，种子库 create

- 状态：已接受
- 日期：2026-08-02

## 背景

最初所有服务都是 `ddl-auto=create`，H2 文件库每次启动重建表、数据清空。这对种子库（auth/rbac）是特性（可复现），但对业务库和审计库是缺陷——尤其审计日志重启即丢，直接消解了 ADR-0004 "可追溯" 的卖点。

## 决策

按库性质分两档：

- **`ddl-auto=update`（持久化）**：
  - `audit-service`（审计日志）；
  - `customer-service`（客户数据、审批单）。
- **`ddl-auto=create`（种子库，每次启动重置为干净种子状态）**：
  - `auth-service`（账号种子：admin/user/viewer）；
  - `rbac-service`（角色/权限种子）。

清空手段：`make reset-db`（删除 `./data/*.mv.db` 后重启重建），仍可手动回到种子态。

## 后果

- 审计日志、客户数据、审批单跨重启保留，"可追溯"成为真实能力。
- 种子库每次启动干净可复现，演示/测试不依赖脏数据。
- H2 是演示折中；README 已注明生产应换 PostgreSQL/MySQL 并用 `ddl-auto=validate`。
