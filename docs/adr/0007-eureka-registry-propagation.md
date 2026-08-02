# ADR-0007：Eureka 注册表传播延迟致 503 的加固（踩坑）

- 状态：已接受
- 日期：2026-07-31

## 背景

`make start` 后请求业务接口偶发 `503 No servers available`。根因：网关（依赖服务发现的 lb:// 路由）先启动、业务服务后注册时，两侧默认缓存/拉取间隔太长——Eureka 服务端默认 30s 响应缓存 + 网关默认 30s 拉取间隔，导致网关在相当长时间内解析不到刚注册的实例。

## 决策

- **eureka-server**：`eureka.server.response-cache-update-interval-ms: 3000`（缩短响应缓存）。
- **网关**：`eureka.client.registry-fetch-interval-seconds: 3`（缩短注册表拉取）。
- **三个业务服务**（auth/rbac/customer/audit）：`eureka.instance.prefer-ip-address: true` + `ip-address: 127.0.0.1`（环回注册，必定可达，规避本机多网卡/容器网络歧义）。
- **Makefile**：全部服务就绪后 `sleep 35` 再输出"就绪"提示，覆盖注册传播窗口。

## 后果

- 三套运行模式（裸 jar / Docker Compose / k3s）下启动顺序敏感问题被工程化兜底，演示稳定可复现。
- 代价：启动到可用需要约 35s 的等待（可接受）。
