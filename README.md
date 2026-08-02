# spring-rbac — RBAC Microservices (Spring Boot + Spring Cloud)

A **Spring Boot + Spring Cloud** implementation equivalent to the `rbac-service` (Node.js) project. On top of the original "API gateway + auth + RBAC" three services, it adds the full **Spring Cloud stack** — Eureka (service discovery), Config Server (configuration), and Spring Cloud Gateway (reactive edge gateway). The three business services act as Eureka + Config clients: service addresses are resolved from the registry instead of being hardcoded, and business config is pushed down centrally.

> Tech stack: Java 17 · Spring Boot 3.2.5 · Spring Cloud 2023.0.3 · Spring Cloud Gateway (WebFlux) · Eureka · Config Server (native) · Spring Data JPA · H2 · Maven (multi-module)

## Architecture

```
                ┌──────────────────────────────────────────────────────────┐
   client ────► │  gateway-service :4100  (Spring Cloud Gateway / PEP)       │
                │   • JWT validation (GlobalFilter)                          │
                │   • Edge authz: calls rbac /api/check (lb://rbac-service)   │
                │   • Route forwarding: lb://auth-service / lb://rbac-service / lb://customer-service │
                └───────┬───────────────────────┬───────────────────────┬──────────────────┘
                        │ via Eureka discovery     │                       │
                        ▼ (lb://auth-service)       ▼ (lb://rbac-service)   ▼ (lb://customer-service)
                ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
                │ auth-service :4101│    │ rbac-service :4102│    │ customer-service :4103│
                │ users / JWT issue  │    │ roles(inherit)/perms/check│ │ CRM customers (RBAC-guarded)│
                │ H2: ./data/auth   │    │ H2: ./data/rbac   │    │ H2: ./data/customer   │
                └──────────────────┘    └──────────────────┘    └──────────────────┘

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
| customer-service | 4103 | CRM customer domain (CRUD + search). Authorization delegated to the gateway PEP + RBAC PDP via `customers:read` / `customers:create` / `customers:update` / `customers:delete` | H2 `./data/customer` |
| audit-service | 4104 | Cross-service audit log (append-only). The gateway emits an audit event after each PEP decision, asynchronously and best-effort; this service only persists and serves them to `audit:read` (admin only). Writes are allowed only from the gateway via service discovery (private header); the external route `/api/audit` is GET-only | H2 `./data/audit` |

Internal services register with Eureka; only the gateway is exposed to the outside (PEP pattern).

## Module structure

```
spring-rbac/
├── pom.xml                 # parent (packaging=pom, spring-cloud-dependencies BOM, 6 modules)
├── eureka-server/          # service registry (@EnableEurekaServer)
├── config-server/          # config server (@EnableConfigServer, native backend)
│   └── src/main/resources/config-repo/   # auth-service.yml / rbac-service.yml / customer-service.yml / gateway-service.yml
├── auth-service/           # auth service (Eureka + Config client)
├── rbac-service/           # RBAC service (Eureka + Config client)
├── customer-service/       # CRM customer service (Eureka + Config client); authz via gateway PEP + RBAC
├── gateway-service/        # Spring Cloud Gateway (WebFlux, PEP via GlobalFilter)
├── k8s/spring-rbac.yaml    # k3s / Kubernetes manifests (6 Deployments + 6 Services)
├── docker-compose.yml      # Docker Compose orchestration
├── scripts/demo.sh         # end-to-end demo
├── DOCKER.md               # Docker Compose & k3s runbook + troubleshooting
├── ARCHITECTURE.md         # deep-dive architecture & design decisions
├── docs/adr/               # architecture decision records (PEP/PDP, circuit breaker, audit, persistence)
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
- Node.js 20+ (for the `web/` frontend; optional — `make start` skips the frontend if npm is missing)
- (Docker / k3s optional, for containerized runs)

### 1. Bare jar (local debugging)

```bash
make build        # mvn clean package -DskipTests  → five jars
make start        # start all 5 backends in order + the web frontend on :3000, wait until ready
make demo         # end-to-end demo through gateway :4100
make status       # reachability of the 5 backends + frontend
make stop         # stop everything (frontend included)
```

The frontend is Next.js (`web/`), started by `make start` as a background `next dev` (log `logs/web.log`, PID `.pids/web.pid`), served at <http://localhost:3000>.
It follows the **BFF** pattern: the browser only calls same-origin `/api/*`, and Next rewrites those to the gateway on `:4100` — no CORS involved.
If `:3000` is already taken (e.g. you run `npm run dev` yourself), `make start` detects it and skips instead of fighting for the port.

```bash
make start WITH_WEB=0                                # backends only
make web-start / make web-stop / make web-restart    # control the frontend alone
make web-start WEB_BACKEND=http://localhost:41000    # when the backend is behind the k3s port-forward
```

Startup seeds: `auth` creates three login accounts — `admin/admin123` (full access, incl. `customers:create/update/delete/approve`), `user/user123` (`editor` role, can edit and request deletion (pending approval), incl. `customers:read/create/update/delete`), `viewer/viewer123` (read-only, `viewer` role, incl. `customers:read`). `rbac` creates roles `admin`/`editor`/`viewer` (three tiers, no inheritance), permissions `users:read|write` `roles:read|write` `permissions:read` `customers:read|create|update|delete|approve`, and grants `admin`→`admin`, `user`→`editor`, `viewer`→`viewer`. auth/rbac use `ddl-auto=create`, so every start is a clean seed state (audit/customer DBs use `update` to persist, see below).

**Deletion goes through an approval flow (admin deletes directly):** an admin with `customers:approve` deletes a customer (`customers:delete`) **immediately** — no approval needed. Other roles (e.g. editor) clicking "申请删除 / request delete" create a pending approval request (`approvals` table); an admin approves it on the "审批 Approvals" page to perform the real deletion. The three tiers differ on deletion: viewer cannot touch / editor can request but not approve / admin can both delete directly and approve.

**CRM sample data from CSV (optional, local-only):** if the env var `CRM_SEED_CSV` points to an existing CSV file at `customer-service` startup, the customers table is seeded from that file instead of the 3 built-in samples (mapping: `name`←通讯录姓名/回退微信备注名, `phone`←手机号, `email`←邮箱, `status`←lead, `notes`←来源/微信ID/ID类型/归属地/运营商/匹配方式). Example:

```bash
CRM_SEED_CSV=/path/to/merged-contacts.csv make start
```

For a "set once, always on" local setup, put `CRM_SEED_CSV=/abs/path/to/merged-contacts.csv` in a repo-root `.env` (already gitignored). `make restart`/`make start` auto-reads it — no need to pass the env var each time.

⚠️ The CSV may contain real PII (phone/email/name). Use it only for your **local** instance — never commit the CSV or the `./data` H2 file (already gitignored). The public demo keeps the 3 sample customers.

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
| GET | `/api/customers` | `customers:read` | list customers (paginated) |
| POST | `/api/customers` | `customers:create` | create customer |
| PUT | `/api/customers/{id}` | `customers:update` | update customer |
| DELETE | `/api/customers/{id}` | `customers:delete` | admin (`customers:approve`) deletes directly (200); other roles enter the approval flow (202) |
| GET | `/api/approvals` | `customers:approve` | list approvals (default PENDING, `?status=` optional) |
| POST | `/api/approvals/{id}/approve` | `customers:approve` | approve and perform the real deletion |
| POST | `/api/approvals/{id}/reject` | `customers:approve` | reject approval (optional `note`) |
| GET | `/api/audit` | `audit:read` | query recent audit records (default 200, `?limit=` allowed), admin only |

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
- **Database**: demo uses file H2. `audit-service` and `customer-service` use `ddl-auto=update` so audit logs and customer data **persist across restarts** (`make reset-db` clears them); `auth`/`rbac` still use `ddl-auto=create` (reset to seed each start). Production: PostgreSQL/MySQL with `ddl-auto=validate`.
- **Config backend**: `native` (reads `config-repo/`), no git needed. Production: switch to `git` backend for versioned config.

## Further reading

- `ARCHITECTURE.md` — architecture deep-dive, sequence diagrams, circuit-breaker & fail-closed, Eureka hardening, route-predicate pitfalls.
- `DOCKER.md` — Docker Compose & k3s runbook, including troubleshooting (imagePullPolicy, missing CMD/CrashLoopBackOff, certificate expiry, JAVA_TOOL_OPTIONS injection).
