# ADR-0001：PEP/PDP 分离——网关执行、RBAC 决策

- 状态：已接受
- 日期：2026-07-31

## 背景

微服务中每个业务服务若各自校验权限，策略逻辑分散在各服务、难以统一变更，且容易漏配。需要一个"策略只写一处、所有请求统一裁决"的模型。

## 决策

采用 PEP（Policy Enforcement Point）/ PDP（Policy Decision Point）分离：

- **PEP = 网关（gateway-service:4100）**：`AuthGlobalFilter` 校验 JWT，把 `path + method` 映射为权限（如 `GET /api/customers` → `customers:read`），然后调 RBAC 的 `/api/check?user=&permission=` 问"放行还是拒绝"。
- **PDP = rbac-service:4102**：持有角色/权限/继承关系，`RbacService.resolveEffectivePermissions` 用 BFS 解析角色继承（`viewer`/`editor`/`admin` 三档），返回 `allowed`。
- **下游身份透传**：网关校验通过后把用户名注入 `X-User` 请求头透传给下游，业务服务（如 customer-service）不再自己鉴权，只按角色分档控制（如 `customers:approve` 决定直删还是走审批）。

## 后果

- 策略变更只改 rbac-service 一处，全站生效。
- 网关成为唯一入口，也是唯一信任边界——外部不能绕过网关直连业务服务（裸 jar 模式下靠端口不暴露约束，生产应靠网络策略）。
- 业务服务鉴权逻辑趋近于零，职责单一。
- 代价：PDP 调用落在每次请求的关键路径上，PDP 不可用会直接影响全站——由此引出 ADR-0002 的熔断设计。
