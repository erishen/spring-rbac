# 容器化运行（Docker Compose）

本目录提供 Docker 化运行方式，与 `make start` 裸跑并存、互不影响：
裸跑用 `localhost` 地址，容器化通过 compose 的 `environment` 注入服务名地址，
**不修改任何 `application.yml` 源码**。

## 前提

- 已安装 Docker（含 `docker compose` 子命令）
- 已用 Maven 构建出各服务的 jar（Dockerfile 直接 `COPY target/*.jar`，不在镜像内重新编译）

```bash
make build          # 生成 5 个 *-0.0.1-SNAPSHOT.jar 到各模块 target/
```

## 启动（一键起全部 5 个服务）

```bash
docker compose up --build -d
```

启动顺序由 `depends_on: condition: service_healthy` 串行保障：
`eureka-server` → `config-server` → `auth-service` / `rbac-service` → `gateway-service`。
健康检查用零依赖的端口探测（`/dev/tcp`），网关额外 `sleep 6` 缓冲 Eureka 注册表传播，
替代了裸跑 Makefile 里的 `sleep 35`。

## 验证

```bash
# 网关登录（拿到 token 即全链路通）
curl -s -X POST http://localhost:4100/api/login \
  -H 'content-type: application/json' \
  -d '{"username":"admin","password":"admin123"}'

# 熔断实例状态
curl -s http://localhost:4100/actuator/circuitbreakers

# 服务发现面板
open http://localhost:8761
```

跑完整 demo（需先构建 jar，且容器已在运行）：

```bash
bash scripts/demo.sh
```

## 停止 / 清理

```bash
docker compose down              # 停服务（保留数据卷）
docker compose down -v           # 连数据卷一起删（auth/rbac 的 H2 库重置）
```

## 说明

- **跨容器服务发现**：compose 网络内服务以服务名（`eureka-server` / `config-server` / `auth-service` …）互相解析；
  地址通过 `JAVA_TOOL_OPTIONS` 注入 JVM 系统属性
  （`-Deureka.client.serviceUrl.defaultZone=http://eureka-server:8761/eureka`
  与 `-Dspring.config.import=optional:configserver:http://config-server:8888`），覆盖 yml 里的 `localhost`。
  ⚠️ **不要用扁平 env 变量** `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` / `SPRING_CONFIG_IMPORT`：
  `eureka.client.serviceUrl` 是 Map 类型，扁平 env 无法可靠绑定到 `defaultZone` 键，
  会导致 client 仍连自己容器里的 `localhost`、全部注册失败（详见文末「排错」）。
- **Eureka 实例注册**：容器化时设 `eureka.instance.prefer-ip-address=true`（同样经 `JAVA_TOOL_OPTIONS` 注入），
  让实例以**容器 IP** 注册（与 k3s 用 Pod IP 一致），网关 `lb://` 拿到容器 IP 后直连，
  避开「按 hostname 注册 → Docker DNS 解析不稳定 → 网关 `lb://auth-service` 返回 503」的坑。
  裸跑时用的 `127.0.0.1` 在跨容器下不可达，故容器化须用容器真实 IP。
- **数据持久化**：`auth-service` / `rbac-service` 的 H2 文件库挂载到命名卷 `auth-data` / `rbac-data`，
  容器重启数据不丢；与裸跑的 `./data` 目录隔离，互不干扰。
- **JVM 内存**：统一 `-Xmx256m`，与裸跑 Makefile 一致。
- **actuator**：仅 `gateway-service` 引了 `spring-boot-starter-actuator`（暴露 `circuitbreakers`）；
  其余服务健康检查用端口探测，未引入额外依赖。

## k3s 部署（OrbStack 单节点）

`k8s/spring-rbac.yaml` 提供一套 K8s manifest，把同样的 5 个服务部署到 k3s。
与裸 jar / compose 完全是同一套代码，地址同样通过 env 注入，**不改任何 `application.yml`**。

### 前提

- 本机已用 OrbStack 启用 k3s（OrbStack 应用 → Settings → Kubernetes 启用，或终端 `orb start k8s`）；
  `kubectl` 指向 orbstack context（API 在 `127.0.0.1:26443`）。
- 镜像已存在于节点本地：先 `make k3s-build`（= `docker compose build`，产出 `spring-rbac/<svc>:latest`）。
  **OrbStack 集成特性**：`docker build` 的镜像 k3s 可直接看到，
  因此 manifest 里各容器用 `imagePullPolicy: IfNotPresent`（本机有镜像即直接用、不拉取，
  同时避开 `Never` 在 OrbStack 下偶发无法解析本地镜像的坑），无需推任何 registry。

### 命令

```bash
make k3s-build             # 构建镜像到节点本地（docker compose build）
make k3s-deploy            # kubectl apply -f k8s/spring-rbac.yaml（namespace rbac-demo）
make k3s-status            # 查看 Pod / Service
make k3s-demo              # 端口转发网关（本地 41000→集群 4100）跑 scripts/demo.sh
make k3s-clean             # kubectl delete -f k8s/spring-rbac.yaml
```

### 要点

- **服务发现**：K8s Service 名 `eureka-server` / `config-server` 经 CoreDNS 解析，
  地址同样经 `JAVA_TOOL_OPTIONS` 注入 JVM 系统属性（与 compose 完全一致，不能用扁平 env）。
- **实例注册**：K8s 里设 `eureka.instance.prefer-ip-address=true`（经 `JAVA_TOOL_OPTIONS`），实例注册 **Pod IP**；
  网关 `lb://auth-service` 经 Eureka 拿到 Pod IP 后直连（K8s 网络内 Pod 互通）。
  这与 compose 一致——两者都改为「按 IP 注册」（容器 IP / Pod IP），而非按 hostname 注册。
- **启动顺序**：K8s 无 `depends_on`，改用 `initContainer`（`busybox` + `nc`）保障依赖：
  auth/rbac 等 `config-server:8888` 就绪再启动；网关额外等 `config-server:8888` + `auth-service:4101` + `rbac-service:4102`
  三个端口都通才启动（等价于 compose 的 `depends_on` 拓扑）。Eureka 注册由 client 自动重试。
- **外部访问**：`gateway-service` 用 `NodePort 30410`（OrbStack 下 `http://localhost:30410` 可达）；
  `make k3s-demo` 则改用 `kubectl port-forward` 转发到**本地 41000**（→ 集群 4100），复用 `scripts/demo.sh`
  （由 `DEMO_PORT` 环境变量切换端口，避免与本机残留下 `make start`/`docker-up` 占用的 4100 冲突、造成"假绿"）。
- **前置顺序**：`make k3s-demo` 现在会先自检 ① k3s 是否可达 ② `gateway-service` 是否已部署 ③ Pod 是否全 Running，
  任一不满足会给出明确提示。**务必先 `make k3s-deploy`**，它不会自动部署。
- **数据**：auth/rbac 的 H2 库用 `emptyDir`（Pod 重启即丢，演示够用）；生产换 `PersistentVolumeClaim`。
- **资源限制**：每个容器都设了 `resources`（主容器内存 `256Mi`/`384Mi`、CPU `100m`/`500m`；
  init 容器更小 `cpu: 50m/100m`），避免单 Pod 吃满节点、也满足 kube-score 等静态检查对 CPU limit 的要求。
  需调整直接改 `k8s/spring-rbac.yaml` 各容器 `resources` 段。

### 排错（k3s）

- **`make k3s-demo` 报 `dial tcp 127.0.0.1:26443: connect: connection refused`**
  → k3s 没启动。26443 是 kubeconfig 里 k3s API server 地址，refused 即无进程监听。
  OrbStack 用户先 `orb start k8s`（或应用内 Settings → Kubernetes 启用），确认 `kubectl get nodes` 有节点，再重跑。
- **`make k3s-demo` 报"尚未部署 / gateway-service 不存在"**
  → 漏了 `make k3s-deploy`。k3s-demo 不会自动部署。
- **`kubectl` 报 `x509: certificate has expired`（current time ... is after 2025-...）**
  → k3s 集群的 API server 证书过期（集群是很久前创建的，证书从没轮换）。这不是没启动，是 TLS 握手过不了。
  修法：删掉旧集群重建，OrbStack 会重新签发证书——`orb delete k8s && orb start k8s`（删集群不动 docker 镜像，本应用镜像仍在，可继续 `make k3s-deploy`）。若想先试轻量修复，可 `orb restart k8s` 触发证书轮换，但 CA 若也过期则仍失败，届时再 delete+start。
- **demo 全绿但怀疑不是 k3s（绿得可疑）**
  → 多半是本机残留下 `make start`/`docker-up` 的网关占着 4100，`demo.sh` 打 localhost:4100 命中了残党。
  `make k3s-demo` 现已转发到本地 41000（不与 4100 冲突）；若仍怀疑，先 `make stop` / `make docker-stop` 清掉残党再跑。
- **Pod 一直 `ContainerCreating`（如 eureka-server / config-server 卡 10+ 分钟）或业务 Pod 卡 `Init:0/1`**
  → 几乎一定是 **k3s 没看到本地 `spring-rbac/*` 镜像**。`imagePullPolicy: Never` 在 OrbStack 下偶发无法解析共享的本地镜像，
    kubelet 一直卡在创建容器。本清单已统一改为 `IfNotPresent`（本地有镜像时直接用、不拉取，避开 `Never` 的解析坑）。
  若改完重部署后仍 `ErrImagePull` / `ImagePullBackOff` → 说明镜像不在 OrbStack 的共享存储：先 `docker images | grep spring-rbac` 确认 5 个镜像在；
    不在就 `make k3s-build` 重新打；若在但仍拉不到，重启 OrbStack 让其重新共享镜像，或 `docker save` 后导入 k3s 的 containerd。
  先 `kubectl delete -f k8s/spring-rbac.yaml` 清掉卡死的 Pod，再 `make k3s-deploy` 用新策略重建。
- **eureka-server / config-server 一直 `Completed` → `CrashLoopBackOff`（退出码 0，容器啥也没跑）**
  → 根因：各服务 Dockerfile **只有 `COPY ... app.jar` + `EXPOSE`，没有 `ENTRYPOINT`/`CMD`**；docker-compose 靠 `command:` 兜底能跑，但 k8s manifest 没设 `command` → 容器无进程可跑、立刻退出（`Completed`=exit 0）→ 重启循环。
  已修：k8s 每个容器都补了 `command: ["java","-Xmx256m","-jar","app.jar"]`（与 compose 一致，无需重建镜像）；网关 extra `sleep 5` 缓冲，且其 init container 改为等 config+auth+rbac 三个端口（等价于 compose 的 `depends_on`）。改完 `kubectl delete -f k8s/spring-rbac.yaml && make k3s-deploy` 即可。
- **重建集群后 `kubectl ...` / `make k3s-status` 卡住不动、既不报错也不返回**
  → 不是坏了，是**控制平面还在初始化**。`kubectl` 默认无请求超时，API server 未就绪时会一直等。
  先 `Ctrl-C`，改跑 `kubectl get nodes` 探活：连不上就等 20–40s 重试；返回节点且 `STATUS=Ready` 即集群好了。
  注意顺序：`make k3s-status` 查的是 `rbac-demo` namespace（部署后才存在），**须先 `make k3s-deploy`** 再 status；想盯进度用 `kubectl get pods -n rbac-demo --watch`。

### 非 OrbStack 环境

若不在 OrbStack（k3s 看不到 docker 本地镜像），二选一：

1. 把镜像推到 registry（如 `docker push` 到 Docker Hub / 私有仓库），manifest 改
   `imagePullPolicy: IfNotPresent` 并填完整镜像地址；
2. 或用 `docker save` + `k3s ctr images import` 把镜像导入 k3s 节点。

## 排错：所有 client 都注册不上 Eureka（注册表为空）

**症状**：`curl localhost:8761/eureka/apps` 返回空，`make docker-demo` 在「等待 Eureka 注册」处超时；
容器 `docker compose logs auth-service` 里能看到
`endpoint=DefaultEndpoint{ serviceUrl='http://localhost:8761/eureka/'} ... Connection refused`——
即 client 仍在连自己容器里的 `localhost`，而不是 `eureka-server:8761`。

**根因（Spring Cloud 经典坑）**：`eureka.client.serviceUrl` 是 **Map 类型**，而
`spring.config.import` 是 **bootstrap 期属性**。这两者用**扁平环境变量**
（`EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` / `SPRING_CONFIG_IMPORT`）注入时**不可靠**：

- Map 类型的 `serviceUrl` 无法从单个扁平 env 正确绑定到 `defaultZone` 键，env 被忽略；
- config import 的 URI 在 bootstrap 阶段解析，扁平 env 也常读不到；
- 结果 client 回退到 `application.yml` 里的 `localhost` 默认值，连自己容器 → 注册失败。

`docker compose exec <svc> printenv` 能看到 env 变量明明在容器里，但 Spring 就是没用——这是迷惑点。

**正确做法**：改用 **JVM 系统属性**（`-D`，优先级最高，Map 也能绑），经 `JAVA_TOOL_OPTIONS`
注入（JVM 会自动读取并应用到 `java` 进程）：

```yaml
environment:
  JAVA_TOOL_OPTIONS: "-Deureka.client.serviceUrl.defaultZone=http://eureka-server:8761/eureka -Deureka.instance.prefer-ip-address=true -Dspring.config.import=optional:configserver:http://config-server:8888"
```

`docker-compose.yml` 与 `k8s/spring-rbac.yaml` 当前都已用这套写法。

**快速自检**：`docker compose up -d` 后立刻
`curl -s localhost:8761/eureka/apps/AUTH-SERVICE | head`，若返回 200 且含 `<status>UP</status>`
即正常；若为 404，说明仍没注册上，按上面排查 env 是否真的以系统属性方式注入。
