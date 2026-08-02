# spring-rbac — RBAC 微服务系统（Spring Boot + Spring Cloud）

与 `rbac-service`（Node.js 版）等价的 **Spring Boot + Spring Cloud 实现**：在原有「API 网关 + 认证 + RBAC」三服务之上，加了一套 **Spring Cloud 全家桶**——Eureka 服务注册中心、Config Server 配置中心、Spring Cloud Gateway 响应式网关。三个业务服务作为 Eureka Client + Config Client，服务地址不再写死，而是从注册中心解析；业务配置由配置中心统一下发。

> 技术栈：Java 17 · Spring Boot 3.2.5 · Spring Cloud 2023.0.3 · Spring Cloud Gateway(WebFlux) · Eureka · Config Server(native) · Spring Data JPA · H2 · Maven（多模块）

## 架构

```
                ┌──────────────────────────────────────────────────────────┐
   客户端 ─────► │  gateway-service :4100  (Spring Cloud Gateway / PEP)       │
                │   • 校验 JWT（GlobalFilter）                                 │
                │   • 边缘鉴权：调 rbac /api/check（lb://rbac-service）         │
                │   • 路由转发：lb://auth-service / lb://rbac-service / lb://customer-service │
                └───────┬───────────────────────┬───────────────────────┬──────────────────┘
                        │ 经 Eureka 服务发现        │                       │
                        ▼ (lb://auth-service)       ▼ (lb://rbac-service)   ▼ (lb://customer-service)
                ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
                │ auth-service :4101│    │ rbac-service :4102│    │ customer-service :4103│
                │ 用户 / JWT 签发    │    │ 角色(继承)/权限/判定 │    │ CRM 客户(RBAC 守护)  │
                │ H2: ./data/auth   │    │ H2: ./data/rbac   │    │ H2: ./data/customer   │
                └──────────────────┘    └──────────────────┘    └──────────────────┘

      ┌──────────────────┐      ┌──────────────────┐
      │ eureka-server :8761│      │ config-server :8888 │
      │ 服务注册中心        │      │ 配置中心(native)    │
      └──────────────────┘      └──────────────────┘
```

| 服务 | 端口 | 角色 | 数据库 |
|---|---|---|---|
| eureka-server | 8761 | 服务注册中心 | 无 |
| config-server | 8888 | 配置中心（native 后端，配置在 `config-server/src/main/resources/config-repo/`） | 无 |
| gateway-service | 4100 | Spring Cloud Gateway：JWT 校验(PEP) + 边缘鉴权 + 服务发现路由 | 无 |
| auth-service | 4101 | 用户注册/登录、JWT 签发与校验（Eureka/Config Client） | H2 `./data/auth` |
| rbac-service | 4102 | 角色(含继承)/权限/授权关系、有效权限解析、权限判定（Eureka/Config Client） | H2 `./data/rbac` |
| customer-service | 4103 | CRM 客户域（增删改查 + 检索）。鉴权委托给网关 PEP + RBAC PDP，经 `customers:read` / `customers:create` / `customers:update` / `customers:delete` 四档控制 | H2 `./data/customer` |

内部服务注册到 Eureka，互联网只能打到网关这一道门（PEP 模式）。

## 模块结构

```
spring-rbac/
├── pom.xml                 # 父工程（packaging=pom，含 spring-cloud-dependencies BOM，6 个模块）
├── eureka-server/          # 服务注册中心（@EnableEurekaServer）
├── config-server/          # 配置中心（@EnableConfigServer，native 后端）
│   └── src/main/resources/config-repo/   # auth-service.yml / rbac-service.yml / gateway-service.yml
├── auth-service/           # 认证服务（Eureka Client + Config Client）
├── rbac-service/           # RBAC 服务（Eureka Client + Config Client）
├── gateway-service/        # Spring Cloud Gateway（WebFlux，GlobalFilter 实现 PEP）
├── k8s/spring-rbac.yaml    # k3s / Kubernetes 清单（5 Deployment + 5 Service）
├── docker-compose.yml      # Docker Compose 编排
├── scripts/demo.sh         # 端到端演示
├── DOCKER.md               # Docker Compose 与 k3s 运行手册 + 排错
├── ARCHITECTURE.md         # 架构深潜与设计决策
└── README.md / README.zh.md
```

## RBAC 模型与 PEP/PDP 分离

权限模型是经典的「用户-角色-权限」三元组，支持角色继承（BFS 展开）。设计上把**策略执行（PEP）**放在网关，**策略决策（PDP）**放在 rbac-service：

- 网关只负责「这个请求有没有带合法 token、目标路由需要什么权限」；
- rbac-service 负责「这个用户到底有没有这个权限」。

任何服务的接口要加权限保护，只要在网关路由上挂一个 `required-permission` 即可，权限逻辑收敛在一处，网关保持薄。

## Resilience4j 熔断治理（fail-closed）

网关对 rbac `/api/check` 的调用是同步远程调用，用 **Resilience4j 熔断**（`instance: rbac-check`）保护：

- `COUNT_BASED` 滑动窗口 10、`failureRateThreshold: 50%`、`slowCallDurationThreshold: 800ms`、半开允许调用数 3、熔断打开后自动转半开。
- **fail-closed（默认拒绝）**：熔断打开或 rbac 不可达时，网关**直接返回 403**，绝不返回 200。安全组件失效时，默认拒绝比默认放行更安全。
- 经 Actuator 暴露（`management.endpoints.web.exposure.include: health,circuitbreakers`）。

## 运行

### 前置条件
- JDK 17
- Maven 3.9+
- Node.js 20+（前端 `web/`；未安装也能跑，`make start` 会自动跳过前端）
- （Docker / k3s 为可选项，仅容器化运行需要）

### 1. 裸 jar（本地调试）

```bash
make build        # mvn clean package -DskipTests → 六个 jar
make start        # 后台启动五服务（顺序 eureka→config→auth/rbac/gateway）+ 前端 :3000，等待就绪
make demo         # 端到端演示（全部走网关 :4100）
make status       # 查看六个后端服务 + 前端可达性
make stop         # 停止全部（含前端）
```

前端是 Next.js（`web/`），由 `make start` 以 `next dev` 后台拉起（日志 `logs/web.log`，PID `.pids/web.pid`），页面在 <http://localhost:3000>。
采用 **BFF** 模式：浏览器只请求同源 `/api/*`，Next 服务端 rewrite 到网关 `:4100`，因此没有浏览器跨域问题。
若 `:3000` 已被占用（例如你自己开了 `npm run dev`），`make start` 会检测到并跳过，不会抢端口。

```bash
make start WITH_WEB=0                                # 只跑后端五服务
make web-start / make web-stop / make web-restart    # 单独控制前端
make web-start WEB_BACKEND=http://localhost:41000    # 后端在 k3s 端口转发上时
```

启动即播种：`auth` 建三个登录账号——`admin/admin123`（全权，含 `customers:create/update/delete`）、`user/user123`（可编辑不可删，`editor` 角色，含 `customers:read/create/update`）、`viewer/viewer123`（只读，`viewer` 角色，含 `customers:read`）。`rbac` 建角色 `admin`/`editor`/`viewer`（三档，无继承）、权限 `users:read|write` `roles:read|write` `permissions:read` `customers:read|create|update|delete`，并把 `admin`→`admin`、`user`→`editor`、`viewer`→`viewer` 绑定。H2 用 `ddl-auto=create`，每次启动都是干净种子状态。

### 2. Docker Compose

```bash
make docker-up      # 构建镜像 + compose up -d --build
make docker-demo    # 等待容器就绪后经网关 :4100 跑端到端演示
make docker-logs    # 跟踪查看容器日志
make docker-stop    # compose down（保留数据卷）
```

所有服务地址通过 `JAVA_TOOL_OPTIONS` 注入（扁平 env 无法绑定 Map/bootstrap 属性）。详见 `DOCKER.md`。

### 3. k3s（单节点 / OrbStack）

```bash
orb start k8s                 # OrbStack：启用 k3s（或：k3s server）
make k3s-build                # 构建镜像（docker compose build 产物，k3s 可见）
make k3s-deploy               # kubectl apply -f k8s/spring-rbac.yaml（namespace rbac-demo）
make k3s-status               # kubectl -n rbac-demo get pods,svc
make k3s-demo                 # 端口转发 41000→4100，跑 demo
```

> 若 k3s API server 证书过期（报 `x509: certificate has expired`），重建集群即可：`orb delete k8s && orb start k8s`。三种运行方式并存、共用同一份源码——`application.yml` 从不修改，地址全靠 env / `JAVA_TOOL_OPTIONS` 注入。

## API 一览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/register` | 公开 | 注册用户（密码 PBKDF2 哈希） |
| POST | `/api/login` | 公开 | 登录，返回 JWT |
| GET | `/api/me` | 需登录 | 返回当前用户名 |
| GET | `/api/roles` | `roles:read` | 列出角色 |
| POST | `/api/roles` | `roles:write` | 创建角色（可带 `parentId` 继承） |
| GET/PUT | `/api/roles/{id}` | `roles:read` / `roles:write` | 获取/更新角色 |
| GET | `/api/permissions` | `permissions:read` | 列出权限 |
| POST | `/api/users/{username}/roles` | `users:write` | 给用户分配角色 |
| GET | `/api/users/{username}/roles` | `users:read` | 查看用户角色 |
| POST | `/api/roles/{id}/permissions` | `roles:write` | 给角色分配权限 |
| GET | `/api/check?user=&permission=` | 需登录 | 判定用户是否有效拥有某权限 |

（所有路由都经网关；网关在转发前做 JWT 校验 + 边缘鉴权，无权限直接 403，请求不会到达下游。）

## 设计要点

- **Spring Cloud 全家桶**：
  - **Eureka** 做服务注册与发现，网关通过 `lb://auth-service` / `lb://rbac-service` 按服务名路由，业务服务地址不再写死。
  - **Config Server（native 后端）** 统一下发 `jwt-secret`、数据源等配置；各服务作为 Config Client 通过 `spring.config.import` 拉取。
  - **Spring Cloud Gateway** 替代原手写 `RestTemplate` 转发，响应式（WebFlux）；JWT 校验与边缘鉴权写成 `GlobalFilter`（PEP），转发由框架按路由配置完成。
- **零依赖 JWT（HS256）**：仅用 JDK `Mac`+`Base64` + Spring 自带的 jackson 实现，避免引入 jjwt 与 Spring Boot 自带 jackson 的版本冲突。结构完全符合 RFC 7519。
- **密码哈希 PBKDF2WithHmacSHA256**（JDK 内置），输出 `salt:hash`，不依赖 spring-security。
- **角色继承**：`roles.parent_id` 形成树；`resolveEffectivePermissions` 用 BFS 沿父链递归展开，得到用户全部有效权限。
- **网关即 PEP**：`AuthGlobalFilter` 把 HTTP 方法与所需权限映射（如 customers 域 GET→`customers:read`、POST→`customers:create`、PUT→`customers:update`、DELETE→`customers:delete`）；需鉴权的路由委托 rbac `/api/check` 判定（网关=策略执行点 PEP，rbac=策略决策点 PDP）。

## 注意事项

- **启动顺序**：必须先起 `eureka-server` 与 `config-server`，业务服务依赖它们（服务注册 + 配置拉取）。`make start` 已处理顺序。
- **`server.port` 会被环境变量覆盖**：Spring Boot relaxed binding 会把 `SERVER_PORT`/`SERVER__PORT` 映射成 `server.port`。本机直接 `java -jar` 不受影响；`make` 已用 `env -u SERVER__PORT` 规避。
- **密钥**：`app.jwt-secret` 当前是 demo 明文密钥，三服务共用同一把（经 Config Server 下发）。**禁止直接用于生产**——应改为各服务独立签名密钥，并经密钥管理下发。
- **数据库**：演示用 H2 文件库，每次启动 `ddl-auto=create` 重置为种子状态；生产请换 PostgreSQL/MySQL 并 `ddl-auto=validate`。
- **Config Server 后端**：当前用 `native`（读 classpath 下的 `config-repo/`），无需 git。生产可改 `git` 后端接远程仓库实现配置版本管理。

## 延伸阅读

- `ARCHITECTURE.md` — 架构深潜、时序图、熔断与 fail-closed、Eureka 加固、路由谓词坑。
- `DOCKER.md` — Docker Compose 与 k3s 运行手册，含排错（imagePullPolicy、缺 CMD/CrashLoopBackOff、证书过期、JAVA_TOOL_OPTIONS 注入）。
