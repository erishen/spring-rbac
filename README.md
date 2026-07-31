# spring-rbac — RBAC 微服务系统（Spring Boot + Spring Cloud）

与 `rbac-service`（Node.js 版）等价的 **Spring Boot + Spring Cloud 实现**：在原有「API 网关 + 认证 + RBAC」三服务之上，加了一套 **Spring Cloud 全家桶**——Eureka 服务注册中心、Config Server 配置中心、Spring Cloud Gateway 响应式网关。三个业务服务作为 Eureka Client + Config Client，服务地址不再写死，而是从注册中心解析；业务配置由配置中心统一下发。

> 技术栈：Java 17 · Spring Boot 3.2.5 · Spring Cloud 2023.0.3 · Spring Cloud Gateway(WebFlux) · Eureka · Config Server(native) · Spring Data JPA · H2 · Maven（多模块）

## 架构

```
                ┌──────────────────────────────────────────────────────────┐
   客户端 ─────► │  gateway-service :4100  (Spring Cloud Gateway / PEP)       │
                │   • 校验 JWT（GlobalFilter）                                 │
                │   • 边缘鉴权：调 rbac /api/check（lb://rbac-service）         │
                │   • 路由转发：lb://auth-service / lb://rbac-service           │
                └───────┬───────────────────────┬───────────────────────────┘
                        │ 经 Eureka 服务发现        │
                        ▼ (lb://auth-service)       ▼ (lb://rbac-service)
                ┌──────────────────┐    ┌──────────────────┐
                │ auth-service :4101│    │ rbac-service :4102│
                │ 用户 / JWT 签发    │    │ 角色(继承)/权限/判定 │
                │ H2: ./data/auth   │    │ H2: ./data/rbac   │
                └──────────────────┘    └──────────────────┘

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

内部服务注册到 Eureka，互联网只能打到网关这一道门（PEP 模式）。

## 模块结构

```
spring-rbac/
├── pom.xml                 # 父工程（packaging=pom，含 spring-cloud-dependencies BOM，5 个模块）
├── eureka-server/          # 服务注册中心（@EnableEurekaServer）
├── config-server/          # 配置中心（@EnableConfigServer，native 后端）
│   └── src/main/resources/config-repo/   # auth-service.yml / rbac-service.yml / gateway-service.yml
├── auth-service/           # 认证服务（Eureka Client + Config Client）
├── rbac-service/           # RBAC 服务（Eureka Client + Config Client）
├── gateway-service/        # Spring Cloud Gateway（WebFlux，GlobalFilter 实现 PEP）
├── scripts/demo.sh         # 端到端演示
└── README.md
```

## 运行

### 1. 编译

```bash
mvn clean package -DskipTests
```

> 首次构建需联网从 Maven Central 下载 Spring Cloud 依赖；之后可 `mvn -o ...` 离线。

### 2. 一键启动（后台，顺序固定）

```bash
make start
```

启动顺序：`eureka-server`(8761) → `config-server`(8888) → `auth-service`(4101) / `rbac-service`(4102) / `gateway-service`(4100)。
`make start` 会先等注册中心与配置中心就绪，再起业务服务，最后等网关 `/health` 可用。

也可各开终端手动起（注意顺序）：

```bash
java -jar eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar
java -jar rbac-service/target/rbac-service-0.0.1-SNAPSHOT.jar
java -jar gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar
```

> 启动即播种：auth 建 `admin/admin123`；rbac 建角色 `admin`/`user`/`viewer`（`viewer` 继承 `user`）、
> 权限 `users:read|write` `roles:read|write` `permissions:read`，并把 `admin` 用户挂到 `admin` 角色。
> H2 用 `ddl-auto=create`，每次启动都是干净种子状态。

### 3. 端到端演示（全部走网关 :4100）

```bash
make demo
```

或手动（见脚本 `scripts/demo.sh`）：登录、建角色、注册 alice、授予 `viewer`、验证 alice 经继承得到 `roles:read`、建角色被 403、`/api/check` 判定、`无 token` 401。

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
- **网关即 PEP**：`AuthGlobalFilter` 把 HTTP 方法与所需权限映射；需鉴权的路由委托 rbac `/api/check` 判定（网关=策略执行点 PEP，rbac=策略决策点 PDP）。

## 注意事项

- **启动顺序**：必须先起 `eureka-server` 与 `config-server`，业务服务依赖它们（服务注册 + 配置拉取）。`make start` 已处理顺序。
- **`server.port` 会被环境变量覆盖**：Spring Boot relaxed binding 会把 `SERVER_PORT`/`SERVER__PORT` 映射成 `server.port`。本机直接 `java -jar` 不受影响；沙箱环境若注入了 `SERVER__PORT`，`make` 已用 `env -u SERVER__PORT` 规避。
- **密钥**：`app.jwt-secret` 当前是 demo 明文密钥，三服务共用同一把（经 Config Server 下发）。生产应改由各服务独立签名密钥，并经配置中心/密钥管理下发。
- **数据库**：演示用 H2 文件库，每次启动 `ddl-auto=create` 重置为种子状态；生产请换 PostgreSQL/MySQL 并 `ddl-auto=validate`。
- **Config Server 后端**：当前用 `native`（读 classpath 下的 `config-repo/`），无需 git。生产可改 `git` 后端接远程仓库实现配置版本管理。
