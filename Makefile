# spring-rbac — RBAC 微服务系统（Spring Boot + Spring Cloud）便捷命令入口
#
# 五个服务（启动顺序固定：先基础设施，后业务）：
#   eureka-server    端口 8761  服务注册中心
#   config-server    端口 8888  配置中心（native 后端）
#   gateway-service  端口 4100  API 网关 / PEP（JWT 校验 + 边缘鉴权 + 服务发现路由）
#   auth-service     端口 4101  认证（注册/登录/签发 JWT）
#   rbac-service     端口 4102  RBAC（角色/权限/鉴权判定）
#
# 用法：make <target>，默认 make help

SHELL := /bin/bash
.DEFAULT_GOAL := help

MVN  := mvn
JAVA := java
JAVA_OPTS := -Xmx256m

EUREKA_JAR := eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar
CONFIG_JAR := config-server/target/config-server-0.0.1-SNAPSHOT.jar
AUTH_JAR   := auth-service/target/auth-service-0.0.1-SNAPSHOT.jar
RBAC_JAR   := rbac-service/target/rbac-service-0.0.1-SNAPSHOT.jar
GW_JAR     := gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar

LOG_DIR := logs
PID_DIR := .pids

.PHONY: help build compile start dev stop restart status demo reset-db clean docker-build docker-up docker-start docker-stop docker-down docker-reset docker-logs docker-ps docker-demo k3s-build k3s-apply k3s-deploy k3s-status k3s-demo k3s-clean

help: ## 显示本帮助
	@echo "spring-rbac — RBAC 微服务系统（Spring Boot + Spring Cloud）可用命令："
	@echo ""
	@echo "  make build     编译打包，生成五个可执行 jar（mvn clean package -DskipTests）"
	@echo "  make compile   仅编译（不打包）"
	@echo "  make start     后台启动五服务（顺序 eureka→config→auth/rbac/gateway），等待就绪"
	@echo "  make dev       重新编译并后台启动（改码后用）"
	@echo "  make stop      停止全部后台服务"
	@echo "  make restart   停止并重新启动"
	@echo "  make status    检查五个服务的健康/可达状态"
	@echo "  make demo      端到端演示（需先 make start）"
	@echo "  make reset-db  清空本地 H2 数据库（下次启动重建种子）"
	@echo "  make clean     停止服务 + mvn clean + 清空数据库"
	@echo ""
	@echo "  make docker-build  先 make build 再构建全部 Docker 镜像"
	@echo "  make docker-up     构建并后台启动全部服务（compose up -d --build）"
	@echo "  make docker-stop   停止并移除容器（保留数据卷）"
	@echo "  make docker-reset  停止并删除数据卷（清空 H2 数据库）"
	@echo "  make docker-ps     查看容器状态"
	@echo "  make docker-logs   跟踪查看容器日志"
	@echo "  make docker-demo   容器启动后经网关跑端到端演示"
	@echo ""
	@echo "  make k3s-build     构建镜像到节点本地（docker compose build 产物，k3s 可见）"
	@echo "  make k3s-deploy    部署到 k3s（namespace rbac-demo）"
	@echo "  make k3s-status    查看 k3s 下 Pod / Service"
	@echo "  make k3s-demo      端口转发网关并跑 RBAC 演示"
	@echo "  make k3s-clean     从 k3s 清理（删除 namespace rbac-demo）"
	@echo ""
	@echo "提示：先 make start，另开终端 make demo 看完整链路。"
	@echo "      demo 非幂等（重复跑会撞 409），想反复演示先 make reset-db。"
	@echo "两种运行方式并存：裸 jar（make start）用于本地调试，Docker（make docker-up）用于分发/演示。"

build: ## 编译打包，生成五个可执行 jar
	$(MVN) -q clean package -DskipTests

compile: ## 仅编译（不打包）
	$(MVN) -q compile

start: ## 后台启动五服务，等待就绪
	@if [ ! -f $(EUREKA_JAR) ] || [ ! -f $(CONFIG_JAR) ] || [ ! -f $(AUTH_JAR) ] || [ ! -f $(RBAC_JAR) ] || [ ! -f $(GW_JAR) ]; then echo "jar 缺失，先编译..."; $(MAKE) build; fi
	@mkdir -p $(LOG_DIR) $(PID_DIR)
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(EUREKA_JAR) > $(LOG_DIR)/eureka.log 2>&1 & echo $$! > $(PID_DIR)/eureka.pid
	@echo "  启动 eureka-server     (8761)，PID $$(cat $(PID_DIR)/eureka.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(CONFIG_JAR) > $(LOG_DIR)/config.log 2>&1 & echo $$! > $(PID_DIR)/config.pid
	@echo "  启动 config-server     (8888)，PID $$(cat $(PID_DIR)/config.pid)"
	@echo "等待基础设施就绪（eureka 8761 / config 8888）..."
	@for i in $$(seq 1 60); do \
	   eu=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8761/eureka/apps 2>/dev/null); \
	   cf=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8888/actuator/health 2>/dev/null); \
	   if [ "$$eu" != "000" ] && [ "$$cf" != "000" ]; then echo "  基础设施就绪 ✅ (eureka=$$eu config=$$cf)"; break; fi; \
	   sleep 1; \
	   if [ $$i -eq 60 ]; then echo "  超时未就绪，查看日志: tail -f $(LOG_DIR)/eureka.log $(LOG_DIR)/config.log"; fi; \
	 done
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(AUTH_JAR) > $(LOG_DIR)/auth.log 2>&1 & echo $$! > $(PID_DIR)/auth.pid
	@echo "  启动 auth-service      (4101)，PID $$(cat $(PID_DIR)/auth.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(RBAC_JAR) > $(LOG_DIR)/rbac.log 2>&1 & echo $$! > $(PID_DIR)/rbac.pid
	@echo "  启动 rbac-service      (4102)，PID $$(cat $(PID_DIR)/rbac.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(GW_JAR) > $(LOG_DIR)/gateway.log 2>&1 & echo $$! > $(PID_DIR)/gateway.pid
	@echo "  启动 gateway-service   (4100)，PID $$(cat $(PID_DIR)/gateway.pid)"
	@echo "等待业务服务就绪..."
	@for i in $$(seq 1 60); do \
	   gw=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4100/health 2>/dev/null); \
	   au=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 -X POST http://127.0.0.1:4101/api/login -H 'content-type: application/json' -d '{"username":"x","password":"y"}' 2>/dev/null); \
	   rb=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4102/api/permissions 2>/dev/null); \
	   if [ "$$gw" = "200" ] && [ "$$au" != "000" ] && [ "$$rb" != "000" ]; then echo "  业务就绪 ✅ (gateway=$$gw auth=$$au rbac=$$rb)"; break; fi; \
	   sleep 1; \
	   if [ $$i -eq 60 ]; then echo "  超时未全部就绪，查看日志: tail -f $(LOG_DIR)/*.log"; fi; \
	 done
	@sleep 35; echo "  额外等待 35s，确保 Eureka 注册表传播与网关负载均衡缓存刷新（lb 解析就绪）"

dev: ## 重新编译并后台启动（改码后用）
	$(MAKE) build
	$(MAKE) start

stop: ## 停止全部后台服务
	@echo "停止服务..."
	@for s in eureka config auth rbac gateway; do \
	   if [ -f $(PID_DIR)/$$s.pid ]; then kill $$(cat $(PID_DIR)/$$s.pid) 2>/dev/null || true; rm -f $(PID_DIR)/$$s.pid; fi; \
	 done
	@pkill -f "eureka-server-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "config-server-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "auth-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "rbac-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "gateway-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@echo "已停止。"

restart: stop start ## 停止并重新启动

status: ## 检查五个服务的健康/可达状态
	@for spec in "8761 GET /eureka/apps" "8888 GET /actuator/health" "4100 GET /health" "4101 POST /api/login" "4102 GET /api/permissions"; do \
	   port=$$(echo $$spec | cut -d' ' -f1); \
	   method=$$(echo $$spec | cut -d' ' -f2); \
	   path=$$(echo $$spec | cut -d' ' -f3); \
	   if [ "$$method" = "POST" ]; then \
	     code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 -X POST http://127.0.0.1:$$port$$path -H 'content-type: application/json' -d '{"username":"x","password":"y"}' 2>/dev/null); \
	   else \
	     code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:$$port$$path 2>/dev/null); \
	   fi; \
	   if [ "$$code" = "000" ]; then echo "  端口 $$port ($$method $$path): 未响应 ($$code)"; \
	   else echo "  端口 $$port ($$method $$path): 可达 ($$code)"; fi; \
	 done

demo: ## 端到端演示（需先 make start）
	@bash scripts/demo.sh

reset-db: ## 清空本地 H2 数据库（下次启动重建种子）
	@if [ -d data ]; then rm -rf data; echo "已清空 data/（下次启动重建种子）"; else echo "data/ 不存在，无需清理"; fi

clean: stop ## 停止服务 + 清理构建产物与数据库
	@rm -rf data
	@$(MVN) -q clean
	@echo "清理完成（target/ 与 data/ 已移除）。"

# ============================================================
# Docker Compose 容器化（多容器一键编排）
# 镜像由各服务目录的 Dockerfile 构建（COPY target/*.jar，需先 make build 生成 jar）
# 与裸 jar 运行（make start）并存，互不干扰。
# ============================================================
DOCKER_COMPOSE := docker compose

docker-build: build ## 先 make build 生成 jar，再构建全部 Docker 镜像
	@echo "构建 Docker 镜像（docker compose build）..."
	$(DOCKER_COMPOSE) build

docker-up: build ## 构建镜像并后台启动全部服务（compose up -d --build）
	@echo "docker compose up -d --build ..."
	$(DOCKER_COMPOSE) up -d --build
	@echo "已在后台启动。查看状态: make docker-ps；查看日志: make docker-logs"

docker-start: docker-up ## 同 docker-up

docker-stop: ## 停止并移除容器（保留数据卷）
	@echo "docker compose down ..."
	$(DOCKER_COMPOSE) down

docker-down: docker-stop ## 同 docker-stop

docker-reset: ## 停止并删除数据卷（清空 H2 数据库，下次启动重建种子）
	@echo "docker compose down -v ..."
	$(DOCKER_COMPOSE) down -v

docker-logs: ## 跟踪查看全部容器日志（Ctrl-C 退出）
	$(DOCKER_COMPOSE) logs -f

docker-ps: ## 查看容器状态
	$(DOCKER_COMPOSE) ps

docker-demo: docker-up ## 启动后经网关跑 RBAC 演示（等待容器就绪）
	@echo "等待 Eureka 注册 auth-service 实例（网关 lb 解析前置）..."
	@for i in $$(seq 1 60); do \
	   code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8761/eureka/apps/AUTH-SERVICE 2>/dev/null); \
	   if [ "$$code" = "200" ]; then echo "  Eureka 已注册 auth-service ✅"; break; fi; \
	   sleep 2; \
	   if [ $$i -eq 60 ]; then echo "  超时：Eureka 未注册 auth-service（诊断: make docker-logs）"; exit 1; fi; \
	 done
	@echo "等待网关可登录（POST /api/login => 200）..."
	@for i in $$(seq 1 60); do \
	   resp=$$(curl -s --noproxy 127.0.0.1,localhost -w "\n__HTTP__%{http_code}" --max-time 3 -X POST http://127.0.0.1:4100/api/login -H 'content-type: application/json' -d '{"username":"admin","password":"admin123"}' 2>/dev/null); \
	   code=$$(echo "$$resp" | sed -n 's/.*__HTTP__\([0-9]*\).*/\1/p'); \
	   if [ "$$code" = "200" ]; then echo "  网关就绪 ✅"; break; fi; \
	   if [ $$i -eq 60 ]; then echo "  网关登录超时（最后 HTTP=$$code）"; echo "  响应体: $$resp"; echo "  诊断: make docker-logs"; exit 1; fi; \
	   sleep 2; \
	 done
	@bash scripts/demo.sh

# ============================================================
# k3s 部署（单节点 / OrbStack 友好）
# 镜像沿用 docker compose build 的产物（OrbStack 下 k3s 可直接看到 docker 本地镜像）
# 与裸 jar / compose 并存，互不干扰。实跑在你本机（需 orb start k8s 启用 k3s）。
# ============================================================
k3s-build: docker-build ## 构建镜像到节点本地（docker compose build 产物，k3s 可见）

k3s-apply k3s-deploy: ## 部署到 k3s（namespace rbac-demo）
	@kubectl apply -f k8s/spring-rbac.yaml
	@echo "已部署。查看状态: make k3s-status"

k3s-status: ## 查看 k3s 下 Pod / Service 状态
	@kubectl -n rbac-demo get pods,svc

k3s-demo: ## 端口转发网关并跑 RBAC 演示（先确保 k3s 已起、已部署、Pod 就绪）
	@echo "检查 k3s 是否可达..."
	@if ! kubectl cluster-info >/dev/null 2>&1; then \
	  echo "  ⚠️ kubectl 连不上 k3s API server（connection refused）。"; \
	  echo "  → 说明 k3s 没启动。OrbStack 用户请先："; \
	  echo "      orb start k8s"; \
	  echo "    或在 OrbStack 应用里启用 Kubernetes，再重跑 make k3s-demo。"; \
	  echo "  → 非 OrbStack：先起 k3s（如 k3s server），确认 kubectl get nodes 有节点。"; \
	  exit 1; \
	fi
	@echo "检查是否已部署 gateway-service..."
	@if ! kubectl -n rbac-demo get svc gateway-service >/dev/null 2>&1; then \
	  echo "  ⚠️ rbac-demo/gateway-service 不存在，尚未部署。"; \
	  echo "  → 请先：make k3s-deploy"; \
	  exit 1; \
	fi
	@echo "等待 Pod 全部 Running..."
	@for i in $$(seq 1 60); do \
	   total=$$(kubectl -n rbac-demo get pods --no-headers 2>/dev/null | wc -l | tr -d ' '); \
	   running=$$(kubectl -n rbac-demo get pods --no-headers 2>/dev/null | awk '$$3=="Running"' | wc -l | tr -d ' '); \
	   if [ "$$total" != "0" ] && [ "$$running" = "$$total" ]; then echo "  Pod 全部 Running ($$running/$$total) ✅"; break; fi; \
	   sleep 3; \
	   if [ $$i -eq 60 ]; then echo "  超时：Pod 未全部就绪，查看 make k3s-status"; exit 1; fi; \
	 done
	@echo "端口转发网关（本地 41000 → 集群 4100），运行 demo..."
	@kubectl -n rbac-demo port-forward svc/gateway-service 41000:4100 & \
	  PFPID=$$!; \
	  for i in $$(seq 1 30); do \
	    code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:41000/health 2>/dev/null); \
	    if [ "$$code" = "200" ]; then echo "  端口转发就绪 ✅"; break; fi; \
	    sleep 1; \
	    if [ $$i -eq 30 ]; then echo "  端口转发未就绪（gateway 可能没起来，查看 make k3s-status）"; kill $$PFPID 2>/dev/null; exit 1; fi; \
	  done; \
	  DEMO_PORT=41000 bash scripts/demo.sh; RC=$$?; kill $$PFPID 2>/dev/null; exit $$RC

k3s-clean: ## 从 k3s 清理（删除 namespace rbac-demo）
	@kubectl delete -f k8s/spring-rbac.yaml
	@echo "已清理 rbac-demo。"
