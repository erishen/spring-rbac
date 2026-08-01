# spring-rbac — RBAC Microservices (Spring Boot + Spring Cloud)

A **Spring Boot + Spring Cloud** implementation equivalent to the `rbac-service` (Node.js) project. On top of the original "API gateway + auth + RBAC" three services, it adds the full **Spring Cloud stack** — Eureka (service discovery), Config Server (configuration), and Spring Cloud Gateway (reactive edge gateway). The three business services act as Eureka + Config clients: service addresses are resolved from the registry instead of being hardcoded, and business config is pushed down centrally.

> Tech stack: Java 17 · Spring Boot 3.2.5 · Spring Cloud 2023.0.3 · Spring Cloud Gateway (WebFlux) · Eureka · Config Server (native) · Spring Data JPA · H2 · Maven (multi-module)

## Architecture

```
                ┌──────────────────────────────────────────────────────────┐
   client ────► │  gateway-service :4100  (Spring Cloud Gateway / PEP)       │
                │   • JWT validation (GlobalFilter)                          │
                │   • Edge authz: calls rbac /api/check (lb://rbac-service)   │
                │   • Route forwarding: lb://auth-service / lb://rbac-service │
                └───────┬───────────────────────┬───────────────────────────┘
                        │ via Eureka discovery     │
                        ▼ (lb://auth-service)       ▼ (lb://rbac-service)
                ┌──────────────────┐    ┌──────────────────┐
                │ auth-service :4101│    │ rbac-service :4102│
                │ users / JWT issue  │    │ roles(inherit)/perms/check│
                │ H2: ./data/auth   │    │ H2: ./data/rbac   │
                └──────────────────┘    └──────────────────┘

      ┌──────────────────┐      ┌──────────────────┐
      │ eureka-server :8761│      │ config-server :8888 │
      │ service registry   │      │ config server (native)│
      └──────────────────┘      └──────────────────┘
```

| Service | Port | Role | DB |
|---|---|---|---|
| eureka-server | 8761 | Service registry | — |
| config-server | 8888 | Config server (native; configs in `config-server/src/main/resources/config-repo/`) | — |
| gateway-service | 4100 | Spring Cloud Gateway: JWT validation (PEP) + edge authorization + service-discovery routing | — |
| auth-service | 4101 | User register/login, JWT issue & validation (Eureka/Config client) | H2 `./data/auth` |
| rbac-service | 4102 | Roles (with inheritance) / permissions / grants, effective-permission resolution, permission check (Eureka/Config client) | H2 `./data/rbac` |

Internal services register with Eureka; only the gateway is exposed to the outside (PEP pattern).

## Module structure

```
spring-rbac/
├── pom.xml                 # parent (packaging=pom, spring-cloud-dependencies BOM, 5 modules)
├── eureka-server/          # service registry (@EnableEurekaServer)
├── config-server/          # config server (@EnableConfigServer, native backend)
│   └── src/main/resources/config-repo/   # auth-service.yml / rbac-service.yml / gateway-service.yml
├── auth-service/           # auth service (Eureka + Config client)
├── rbac-service/           # RBAC service (Eureka + Config client)
├── gateway-service/        # Spring Cloud Gateway (WebFlux, PEP via GlobalFilter)
├── k8s/spring-rbac.yaml    # k3s / Kubernetes manifests (5 Deployments + 5 Services)
├── docker-compose.yml      # Docker Compose orchestration
├── scripts/demo.sh         # end-to-end demo
├── DOCKER.md               # Docker Compose & k3s runbook + troubleshooting
├── ARCHITECTURE.md         # deep-dive architecture & design decisions
└── README.md
```

## RBAC model & PEP/PDP

Classic *user–role–permission* triple with **role inheritance** (BFS expansion). Authorization is split:

- **PEP (Policy Enforcement Point)** lives in the gateway — it checks whether the request carries a valid JWT and which permission the target route requires.
- **PDP (Policy Decision Point)** lives in `rbac-service` — it answers *does this user effectively have this permission*.

Adding a protected route is just one `required-permission` mapping in the gateway; permission logic stays in one place.

## Resilience4j circuit breaker (fail-closed)

The gateway's call to `rbac /api/check` is a synchronous cross-service call, so it is wrapped in a **Resilience4j circuit breaker** (`instance: rbac-check`):

- `COUNT_BASED` sliding window of 10, `failureRateThreshold: 50%`, `slowCallDurationThreshold: 800ms`, half-open permitted calls 3, open→half-open auto-transition.
- **Fail-closed**: when the breaker is open (or rbac is unreachable), the gateway returns **403**, never 200. In a security component, deny-by-default beats allow-by-default.
- Exposed via Actuator (`management.endpoints.web.exposure.include: health,circuitbreakers`).

## Running

### Prerequisites
- JDK 17
- Maven 3.9+
- (Docker / k3s optional, for containerized runs)

### 1. Bare jar (local debugging)

```bash
make build        # mvn clean package -DskipTests  → five jars
make start        # start all 5 in order, wait until ready
make demo         # end-to-end demo through gateway :4100
```

Startup seeds: `auth` creates `admin/admin123`; `rbac` creates roles `admin`/`user`/`viewer` (`viewer` inherits `user`), permissions `users:read|write` `roles:read|write` `permissions:read`, and grants `admin` → `admin` role. H2 uses `ddl-auto=create`, so every start is a clean seed state.

### 2. Docker Compose

```bash
make docker-up      # build images + compose up -d --build
make docker-demo    # wait for readiness, run demo through gateway :4100
make docker-logs    # tail logs
make docker-stop    # compose down (keeps volumes)
```

All service addresses are injected via `JAVA_TOOL_OPTIONS` (flat env vars cannot bind Map/bootstrap properties). See `DOCKER.md`.

### 3. k3s (single-node / OrbStack)

```bash
orb start k8s                 # OrbStack: enable k3s (or: k3s server)
make k3s-build                # build images (docker compose build; k3s sees them)
make k3s-deploy               # kubectl apply -f k8s/spring-rbac.yaml (ns rbac-demo)
make k3s-status               # kubectl -n rbac-demo get pods,svc
make k3s-demo                 # port-forward 41000→4100, run demo
```

> If the k3s API server certificate has expired (`x509: certificate has expired`), recreate the cluster: `orb delete k8s && orb start k8s`. All three run modes coexist and share the same source — `application.yml` is never edited; everything is injected via env / `JAVA_TOOL_OPTIONS`.

## API reference

| Method | Path | Authz | Description |
|---|---|---|---|
| POST | `/api/register` | public | register user (PBKDF2 password hash) |
| POST | `/api/login` | public | login, returns JWT |
| GET | `/api/me` | login | current username |
| GET | `/api/roles` | `roles:read` | list roles |
| POST | `/api/roles` | `roles:write` | create role (optional `parentId` for inheritance) |
| GET/PUT | `/api/roles/{id}` | `roles:read` / `roles:write` | get / update role |
| GET | `/api/permissions` | `permissions:read` | list permissions |
| POST | `/api/users/{username}/roles` | `users:write` | assign role to user |
| GET | `/api/users/{username}/roles` | `users:read` | user's roles |
| POST | `/api/roles/{id}/permissions` | `roles:write` | grant permission to role |
| GET | `/api/check?user=&permission=` | login | whether user effectively has a permission |

All routes go through the gateway; the gateway validates JWT + does edge authorization before forwarding. No permission → 403, request never reaches downstream.

## Design highlights

- **Spring Cloud stack**: Eureka for discovery (`lb://auth-service`, `lb://rbac-service`); Config Server (native) pushes `jwt-secret` / datasource; Gateway replaces hand-written forwarding with reactive routing + `GlobalFilter` PEP.
- **Zero-dependency JWT (HS256)**: implemented with JDK `Mac` + `Base64` + Jackson — avoids jjwt/Spring-Boot Jackson version conflicts. RFC 7519 compliant.
- **Password hash PBKDF2WithHmacSHA256** (JDK built-in), stored as `salt:hash`, no spring-security.
- **Role inheritance**: `roles.parent_id` tree; `resolveEffectivePermissions` expands via BFS along the parent chain.
- **Gateway = PEP**: `AuthGlobalFilter` maps HTTP method → required permission; protected routes delegate to rbac `/api/check` (gateway=PEP, rbac=PDP).

## Notes & security

- **Start order**: eureka + config first (services depend on them). `make start` handles it.
- **`server.port` override**: Spring relaxed binding maps `SERVER_PORT`/`SERVER__PORT` → `server.port`. `make` uses `env -u SERVER__PORT` to avoid sandbox interference.
- **`jwt-secret`**: currently a demo plaintext key shared by three services via Config Server. **Do not use in production** — move to per-service keys from a secret manager.
- **Database**: demo uses file H2, reset to seed each start (`ddl-auto=create`). Production: PostgreSQL/MySQL with `ddl-auto=validate`.
- **Config backend**: `native` (reads `config-repo/`), no git needed. Production: switch to `git` backend for versioned config.

## Further reading

- `ARCHITECTURE.md` — architecture deep-dive, sequence diagrams, circuit-breaker & fail-closed, Eureka hardening, route-predicate pitfalls.
- `DOCKER.md` — Docker Compose & k3s runbook, including troubleshooting (imagePullPolicy, missing CMD/CrashLoopBackOff, certificate expiry, JAVA_TOOL_OPTIONS injection).
