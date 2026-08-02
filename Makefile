# spring-rbac — RBAC 微服务系统（Spring Boot + Spring Cloud）便捷命令入口
#
# 六个服务（启动顺序固定：先基础设施，后业务）：
#   eureka-server    端口 8761  服务注册中心
#   config-server    端口 8888  配置中心（native 后端）
#   gateway-service  端口 4100  API 网关 / PEP（JWT 校验 + 边缘鉴权 + 服务发现路由）
#   auth-service     端口 4101  认证（注册/登录/签发 JWT）
#   rbac-service     端口 4102  RBAC（角色/权限/鉴权判定，作为 PDP）
#   customer-service 端口 4103  CRM 客户域（鉴权委托给网关 PEP + RBAC PDP）
# 外加前端：
#   web              端口 3000  Next.js（BFF：/api/* 经 rewrite 代理到网关 4100）
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
CUST_JAR   := customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
GW_JAR     := gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar

LOG_DIR := logs
PID_DIR := .pids

# --- 前端（Next.js，裸 jar 模式下以 dev server 方式随 make start 一起跑）---
WEB_DIR     := web
WEB_PORT    ?= 3000
WEB_BACKEND ?= http://localhost:4100
NPM         := npm
# make start/stop 是否带前端；WITH_WEB=0 则只跑后端六服务
# （注意：变量值行不要写行尾注释，make 会把注释前的空格算进变量值）
WITH_WEB    ?= 1
# 递归调用自身时用 $(SUBMAKE) 而非 $(MAKE)：GNU make 把含字面量 $(MAKE) 的命令行当作递归行，
# 即使 make -n（dry-run）也照样真执行 —— 那会让"只想预览"变成把服务真的启起来。
SUBMAKE     := $(MAKE)

# 本地专用变量（如 CRM_SEED_CSV 指向个人通讯录 CSV）：从仓库根 .env 读取，一次配置永久生效。
# .env 已被 .gitignore 排除，绝不入库；里面只放你本机路径，切勿提交。
ifneq (,$(wildcard .env))
include .env
export CRM_SEED_CSV
endif

.PHONY: help build compile start dev stop restart status demo reset-db clean web-install web-spawn web-wait web-start web-stop web-restart web-killport docker-build docker-up docker-start docker-stop docker-down docker-reset docker-logs docker-ps docker-demo k3s-build k3s-ensure k3s-apply k3s-deploy k3s-status k3s-demo k3s-clean

help: ## 显示本帮助
	@echo "spring-rbac — RBAC 微服务系统（Spring Boot + Spring Cloud）可用命令："
	@echo ""
	@echo "  make build     编译打包，生成六个可执行 jar（mvn clean package -DskipTests）"
	@echo "  make compile   仅编译（不打包）"
	@echo "  make start     后台启动六服务（eureka→config→auth/rbac/customer/gateway）+ 前端 :3000，等待就绪"
	@echo "  make dev       重新编译并后台启动（改码后用）"
	@echo "  make stop      停止全部后台服务（含前端）"
	@echo "  make restart   停止 + 重新编译打包 + 启动（改码后必用，确保吃到新 jar）"
	@echo "                 若仓库根 .env 设了 CRM_SEED_CSV，CRM 会自动注入该通讯录（真实 PII 仅本机）"
	@echo "  make status    检查六个后端服务 + 前端的健康/可达状态"
	@echo "  make demo      端到端演示（需先 make start）"
	@echo "  make reset-db  清空本地 H2 数据库（下次启动重建种子）"
	@echo "  make clean     停止服务 + mvn clean + 清空数据库 + 清 web/.next"
	@echo ""
	@echo "  make web-start    单独启动前端 dev server（:3000，先强制释放被占端口并清缓存，再启动）"
	@echo "  make web-stop     单独停止前端"
	@echo "  make web-restart  重启前端"
	@echo "  （只跑后端: make start WITH_WEB=0 ；改前端端口/后端地址: WEB_PORT=3001 WEB_BACKEND=http://localhost:41000）"
	@echo ""
	@echo "  make docker-build  先 make build 再构建全部 Docker 镜像"
	@echo "  make docker-up     构建并后台启动全部服务（compose up -d --build）"
	@echo "  make docker-stop   停止并移除容器（保留数据卷）"
	@echo "  make docker-reset  停止并删除数据卷（清空 H2 数据库）"
	@echo "  make docker-ps     查看容器状态"
	@echo "  make docker-logs   跟踪查看容器日志"
	@echo "  make docker-demo   容器启动后经网关跑端到端演示（含前端 :3000 可用性检查）"
	@echo ""
	@echo "  make k3s-build     构建镜像到节点本地（docker compose build 产物，k3s 可见）"
	@echo "  make k3s-deploy    部署到 k3s（namespace rbac-demo）"
	@echo "  make k3s-status    查看 k3s 下 Pod / Service"
	@echo "  make k3s-demo      端口转发网关并跑 RBAC 演示"
	@echo "  make k3s-clean     从 k3s 清理（删除 namespace rbac-demo）"
	@echo ""
	@echo "提示：先 make start（后端 + 前端一起起），另开终端 make demo 看完整链路，浏览器开 http://localhost:3000"
	@echo "      demo 非幂等（重复跑会撞 409），想反复演示先 make reset-db。"
	@echo "三套运行方式并存：裸 jar（make start，前端跑 next dev 热更新）本地调试；Docker Compose（make docker-up）/"
	@echo "  k3s（make k3s-deploy）容器化部署。三者都含 Next.js 前端（:3000），容器内经 gateway-service:4100 调网关。"

build: ## 编译打包，生成六个可执行 jar
	$(MVN) -q clean package -DskipTests

compile: ## 仅编译（不打包）
	$(MVN) -q compile

start: ## 后台启动六服务，等待就绪
	@if [ ! -f $(EUREKA_JAR) ] || [ ! -f $(CONFIG_JAR) ] || [ ! -f $(AUTH_JAR) ] || [ ! -f $(RBAC_JAR) ] || [ ! -f $(CUST_JAR) ] || [ ! -f $(GW_JAR) ]; then echo "jar 缺失，先编译..."; $(SUBMAKE) build; fi
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
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(CUST_JAR) > $(LOG_DIR)/customer.log 2>&1 & echo $$! > $(PID_DIR)/customer.pid
	@echo "  启动 customer-service  (4103)，PID $$(cat $(PID_DIR)/customer.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(GW_JAR) > $(LOG_DIR)/gateway.log 2>&1 & echo $$! > $(PID_DIR)/gateway.pid
	@echo "  启动 gateway-service   (4100)，PID $$(cat $(PID_DIR)/gateway.pid)"
	@echo "等待业务服务就绪..."
	@for i in $$(seq 1 60); do \
	   gw=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4100/health 2>/dev/null); \
	   au=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 -X POST http://127.0.0.1:4101/api/login -H 'content-type: application/json' -d '{"username":"x","password":"y"}' 2>/dev/null); \
	   rb=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4102/api/permissions 2>/dev/null); \
	   cu=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4103/api/customers 2>/dev/null); \
	   if [ "$$gw" = "200" ] && [ "$$au" != "000" ] && [ "$$rb" != "000" ] && [ "$$cu" != "000" ]; then echo "  业务就绪 ✅ (gateway=$$gw auth=$$au rbac=$$rb customer=$$cu)"; break; fi; \
	   sleep 1; \
	   if [ $$i -eq 60 ]; then echo "  超时未全部就绪，查看日志: tail -f $(LOG_DIR)/*.log"; fi; \
	 done
	@if [ "$(WITH_WEB)" = "1" ]; then $(SUBMAKE) --no-print-directory web-spawn; else echo "  跳过前端（WITH_WEB=0）"; fi
	@sleep 35; echo "  额外等待 35s，确保 Eureka 注册表传播与网关负载均衡缓存刷新（lb 解析就绪）"
	@if [ "$(WITH_WEB)" = "1" ]; then $(SUBMAKE) --no-print-directory web-wait; fi

dev: ## 重新编译并后台启动（改码后用）
	$(SUBMAKE) build
	$(SUBMAKE) start

stop: ## 停止全部后台服务（含前端）
	@echo "停止服务..."
	@for s in eureka config auth rbac customer gateway; do \
	   if [ -f $(PID_DIR)/$$s.pid ]; then kill $$(cat $(PID_DIR)/$$s.pid) 2>/dev/null || true; rm -f $(PID_DIR)/$$s.pid; fi; \
	 done
	@pkill -f "eureka-server-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "config-server-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "auth-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "rbac-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "customer-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "gateway-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@if [ "$(WITH_WEB)" = "1" ]; then $(SUBMAKE) --no-print-directory web-stop; fi
	@echo "已停止。"

restart: stop build start ## 停止、重新编译打包、再启动（改码后必用，确保吃到新 jar）

# ------------------------------------------------------------
# 前端（Next.js dev server）—— 裸 jar 模式的配套目标
# 容器化模式（compose / k3s）里前端由镜像跑，不走这里。
# 端口 3000；BACKEND_URL 默认 http://localhost:4100（本地网关）。
# k3s 端口转发场景可覆盖：make web-start WEB_BACKEND=http://localhost:41000
# 重要：web-start / web-stop 现在会强制释放 WEB_PORT（杀掉占用端口的任意进程，
#   不再按命令名过滤、也不再"检测到占用就跳过"），并清掉 web/.next 缓存，
#   保证每次起来都是按当前源码重新编译的干净实例。
# ------------------------------------------------------------
web-install: ## 安装前端依赖（node_modules 缺失时才装）
	@if [ ! -d $(WEB_DIR)/node_modules ]; then \
	   echo "  前端依赖缺失，安装中（npm install，首次较慢）..."; \
	   (cd $(WEB_DIR) && $(NPM) install --no-audit --no-fund) || { echo "  ⚠️ npm install 失败，跳过前端"; exit 0; }; \
	 fi

web-spawn: ## 仅后台拉起前端进程（先强制释放端口并清缓存，供 make start 并行用）
	@if ! command -v $(NPM) >/dev/null 2>&1; then echo "  未检测到 npm，跳过前端（装好 Node.js 后再 make web-start）"; exit 0; fi
	@$(SUBMAKE) --no-print-directory web-killport
	@rm -rf $(WEB_DIR)/.next
	@$(SUBMAKE) --no-print-directory web-install
	@mkdir -p $(LOG_DIR) $(PID_DIR)
	@(cd $(WEB_DIR) && BACKEND_URL=$(WEB_BACKEND) nohup $(NPM) run dev -- -p $(WEB_PORT) > $(CURDIR)/$(LOG_DIR)/web.log 2>&1 & echo $$! > $(CURDIR)/$(PID_DIR)/web.pid)
	@echo "  启动 web (Next.js dev)  ($(WEB_PORT))，PID $$(cat $(PID_DIR)/web.pid)，后端 $(WEB_BACKEND)"

web-wait: ## 等待前端就绪（GET /login => 200）
	@if [ ! -f $(PID_DIR)/web.pid ] && [ "$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:$(WEB_PORT)/login 2>/dev/null)" = "000" ]; then exit 0; fi
	@echo "等待前端就绪（http://localhost:$(WEB_PORT)/login）..."
	@for i in $$(seq 1 90); do \
	   c=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:$(WEB_PORT)/login 2>/dev/null); \
	   if [ "$$c" = "200" ]; then echo "  前端就绪 ✅  http://localhost:$(WEB_PORT)"; break; fi; \
	   sleep 1; \
	   if [ $$i -eq 90 ]; then echo "  前端超时未就绪（HTTP=$${c}），查看日志: tail -f $(LOG_DIR)/web.log"; fi; \
	 done

web-start: web-spawn web-wait ## 单独启动前端并等待就绪

web-stop: ## 停止前端 dev server（:3000，强制释放端口）
	@if [ -f $(PID_DIR)/web.pid ]; then kill $$(cat $(PID_DIR)/web.pid) 2>/dev/null || true; rm -f $(PID_DIR)/web.pid; fi
	@$(SUBMAKE) --no-print-directory web-killport
	@echo "  前端已停止（:$(WEB_PORT)）"

web-killport: ## 强制释放前端端口（杀掉占用 WEB_PORT 的任意进程，不限命令名）
	@pids=$$(lsof -ti tcp:$(WEB_PORT) -sTCP:LISTEN 2>/dev/null); \
	 if [ -n "$$pids" ]; then \
	   echo "  释放 :$(WEB_PORT)（杀掉占用进程: $$pids）"; \
	   echo "$$pids" | xargs -r kill -9 2>/dev/null || true; \
	   sleep 1; \
	 else \
	   echo "  :$(WEB_PORT) 当前未被占用"; \
	 fi

web-restart: web-stop web-start ## 重启前端

status: ## 检查六个后端服务 + 前端的健康/可达状态
	@for spec in "8761 GET /eureka/apps" "8888 GET /actuator/health" "4100 GET /health" "4101 POST /api/login" "4102 GET /api/permissions" "4103 GET /api/customers" "$(WEB_PORT) GET /login"; do \
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
	@rm -rf $(WEB_DIR)/.next
	@$(MVN) -q clean
	@echo "清理完成（target/、data/、web/.next 已移除；web/node_modules 保留）。"

# ============================================================
# Docker Compose 容器化（多容器一键编排）
# 后端镜像由各服务目录的 Dockerfile 构建（COPY target/*.jar，需先 make build 生成 jar）；
# Web 前端由 web/Dockerfile 多阶段构建（Next.js），构建期把 BACKEND_URL 烤进 rewrite 目标。
# 与裸 jar 运行（make start）并存，互不干扰。前端在容器网络内经服务名 gateway-service:4100 调网关。
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
	   if [ $$i -eq 60 ]; then echo "  网关登录超时（最后 HTTP=$${code}）"; echo "  响应体: $$resp"; echo "  诊断: make docker-logs"; exit 1; fi; \
	   sleep 2; \
	 done
	@echo "等待前端可访问（GET /login => 200）..."
	@for i in $$(seq 1 60); do \
	   wcode=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:3000/login 2>/dev/null); \
	   if [ "$$wcode" = "200" ]; then echo "  前端就绪 ✅"; break; fi; \
	   sleep 2; \
	   if [ $$i -eq 60 ]; then echo "  前端未就绪（HTTP=$${wcode}），诊断: docker compose logs web"; exit 1; fi; \
	 done
	@bash scripts/demo.sh

# ============================================================
# k3s 部署（单节点 / OrbStack 友好）
# 镜像沿用 docker compose build 的产物（OrbStack 下 k3s 可直接看到 docker 本地镜像）
# 与裸 jar / compose 并存，互不干扰。make k3s-* 会自动 orb start k8s 并确保 apiserver 可达。
# Web 前端一并部署（Deployment + ClusterIP Service），容器内经服务名 gateway-service:4100 调网关。
# ============================================================
k3s-build: docker-build ## 构建镜像到节点本地（docker compose build 产物，k3s 可见）

# 自动确保 OrbStack Kubernetes 已启用且 apiserver 可达（非 OrbStack 环境仅探测，不自动起）
k3s-ensure:
	@if command -v orb >/dev/null 2>&1; then \
	  echo "确保 OrbStack Kubernetes 已启动..."; \
	  orb start k8s >/dev/null 2>&1 || true; \
	else \
	  echo "未检测到 orb CLI，跳过自动启动（非 OrbStack 环境请自行确保集群可达）。"; \
	fi
	@echo "等待 k3s apiserver 可达..."
	@for i in $$(seq 1 40); do \
	  if kubectl cluster-info >/dev/null 2>&1; then echo "  k3s 可达 ✅"; break; fi; \
	  sleep 3; \
	  if [ $$i -eq 40 ]; then echo "  ⚠️ 超时：k3s apiserver 仍不可达（connection refused）。"; \
	    echo "  → 请确认 OrbStack 已安装且 Kubernetes 已启用：orb start k8s"; \
	    echo "  → 或手动起 k3s 后重跑。"; exit 1; fi; \
	 done

k3s-apply k3s-deploy: k3s-ensure ## 部署到 k3s（namespace rbac-demo）
	@kubectl apply -f k8s/spring-rbac.yaml
	@echo "已部署。查看状态: make k3s-status"

k3s-status: ## 查看 k3s 下 Pod / Service 状态
	@kubectl -n rbac-demo get pods,svc

k3s-demo: k3s-ensure ## 端口转发网关并跑 RBAC 演示（先确保 k3s 已起、已部署、Pod 就绪）
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
	@echo "端口转发网关（本地 41000 → 集群 4100）与前端（本地 3000 → 集群 3000），运行 demo..."
	@kubectl -n rbac-demo port-forward svc/gateway-service 41000:4100 & \
	  PFPID1=$$!; \
	  kubectl -n rbac-demo port-forward svc/web 3000:3000 & \
	  PFPID2=$$!; \
	  for i in $$(seq 1 30); do \
	    gwc=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:41000/health 2>/dev/null); \
	    webc=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:3000/login 2>/dev/null); \
	    if [ "$$gwc" = "200" ] && [ "$$webc" = "200" ]; then echo "  网关与前端端口转发就绪 ✅"; break; fi; \
	    sleep 1; \
	    if [ $$i -eq 30 ]; then echo "  转发未就绪（gateway=$$gwc web=$$webc，查看 make k3s-status）"; kill $$PFPID1 $$PFPID2 2>/dev/null; exit 1; fi; \
	  done; \
	  DEMO_PORT=41000 bash scripts/demo.sh; RC=$$?; kill $$PFPID1 $$PFPID2 2>/dev/null; exit $$RC

k3s-clean: ## 从 k3s 清理（删除 namespace rbac-demo）
	@kubectl delete -f k8s/spring-rbac.yaml
	@echo "已清理 rbac-demo。"
