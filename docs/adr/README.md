# 架构决策记录（ADR）

本目录按编号记录本项目的关键架构决策与踩坑结论。每份 ADR 说明背景、决策与后果，便于回顾"当时为什么这么做"。

| 编号 | 标题 | 状态 |
|---|---|---|
| [0001](./0001-pep-pdp-separation.md) | PEP/PDP 分离：网关执行、RBAC 决策 | 已接受 |
| [0002](./0002-fail-closed-circuit-breaker.md) | 网关调 PDP 用 Resilience4j，fail-closed 熔断 | 已接受 |
| [0003](./0003-role-tiers-and-delete-approval.md) | 权限模型：三档角色 + 删除审批 + admin 直删 | 已接受 |
| [0004](./0004-cross-service-audit.md) | 跨服务审计：网关唯一发射 + 独立 audit-service | 已接受 |
| [0005](./0005-persistence-strategy.md) | 持久化策略：业务/审计 update，种子库 create | 已接受 |
| [0006](./0006-gateway-path-or-semantics.md) | 网关路由 Path 谓词：逗号=OR、多谓词=AND（踩坑） | 已接受 |
| [0007](./0007-eureka-registry-propagation.md) | Eureka 注册表传播延迟致 503 的加固（踩坑） | 已接受 |

模板：每个 ADR 固定「背景 / 决策 / 后果」三节，突出可复盘的 why。
