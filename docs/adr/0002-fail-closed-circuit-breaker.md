# ADR-0002：网关调 PDP 用 Resilience4j，fail-closed 熔断

- 状态：已接受
- 日期：2026-07-31

## 背景

ADR-0001 把 PDP 判定放到每次请求的关键路径上。若 rbac-service（PDP）变慢或不可达，网关必须有一个明确行为：不能无限等待，也不能在"无法判定"时放行（那等于放弃鉴权）。

## 决策

- 依赖 `resilience4j-spring-boot3` + `resilience4j-reactor`（2.2.0）。
- 熔断实例 `rbac-check`：`COUNT_BASED` 窗口 10、失败率 50%、慢调用阈值 800ms。
- PEP 的 WebClient 调用用 `CircuitBreakerOperator.of(...)` 包裹。
- **fail-closed 语义**：熔断打开或 PDP 不可达 → 一律返回 403（拒绝），绝不放行。
- actuator 暴露 `circuitbreakers` 端点，可观测熔断状态。

## 后果

- 安全优先：宁可错杀（拒绝），不可漏放（未鉴权放行）。
- 业务服务不可用时网关能快速失败，避免级联等待。
- 演示/排障时可通过 actuator 确认实例状态（CLOSED / OPEN）与 `bufferedCalls` 增长情况。
