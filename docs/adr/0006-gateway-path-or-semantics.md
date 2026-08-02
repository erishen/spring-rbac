# ADR-0006：网关路由 Path 谓词——逗号=OR、多谓词=AND（踩坑）

- 状态：已接受
- 日期：2026-08-02

## 背景

新增审批模块后，「审批 Approvals」页面加载报 **Not Found**。网关路由 `customer-api` 只配置了 `Path=/api/customers/**`，审批请求 `/api/approvals/**` 没有路由可匹配。

修复时踩到一个 Spring Cloud Gateway 的经典语义陷阱：想给同一条路由补多个前缀，于是写成两条 `Path=` 谓词：

```yaml
predicates:
  - Path=/api/customers/**
  - Path=/api/approvals/**   # ❌ 这条会让路由永不匹配！
```

## 决策

**同一路由内的多个 `Path` 谓词是"且（AND）"关系**，即要求路径同时满足两个条件——不可能，于是路由整体失效，所有请求 404。要表达"任一前缀"必须写成**单个**谓词、逗号分隔（逗号=OR）：

```yaml
predicates:
  - Path=/api/customers/**,/api/approvals/**   # ✅ 逗号分隔 = OR
```

同理，`audit-api` 路由用 `Path=/api/audit` + `Method=GET`（多谓词 AND，此处正好表达"只读"意图，符合设计）。

## 后果

- 新前缀接入时先想清楚意图：多前缀匹配用逗号（OR）；多条件交集用多谓词（AND）。
- 本坑已入 README 网关章节提示，避免后人再踩。
