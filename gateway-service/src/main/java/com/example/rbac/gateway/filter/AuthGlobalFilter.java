package com.example.rbac.gateway.filter;

import com.example.rbac.gateway.util.JwtException;
import com.example.rbac.gateway.util.JwtUtil;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * API 网关（PEP 策略执行点，Spring Cloud Gateway GlobalFilter 实现）：
 *  1. 校验 JWT（所有受保护路由）；
 *  2. 按路由表做边缘鉴权：调用 RBAC /api/check 判定权限，无权限直接 403；
 *  3. 通过鉴权后交还给 Gateway，由其按 lb:// 路由转发到 auth / rbac / customer 内部服务；
 *  4. 在 PEP 裁决后【异步、best-effort】发射审计事件到 audit-service，
 *     以网关为唯一发射点实现跨服务审计（fail-open：审计服务不可用不影响业务）。
 * 内部服务只注册在 Eureka，互联网只能打到网关这一道门。
 */
@Component
@Order(-1)
public class AuthGlobalFilter implements GlobalFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    /** 网关内部直连审计服务的私有头，audit-service 据此拒绝外部伪造写入。 */
    private static final String AUDIT_HEADER = "X-Internal-Audit";
    private static final String AUDIT_HEADER_VALUE = "gateway";

    private final JwtUtil jwtUtil;
    private final WebClient lbWebClient;
    private final CircuitBreaker pdpCircuitBreaker;

    public AuthGlobalFilter(@Value("${app.jwt-secret}") String secret,
                            @Value("${app.jwt-ttl:86400000}") long ttl,
                            @Qualifier("lbWebClientBuilder") WebClient.Builder lbWebClientBuilder,
                            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.jwtUtil = new JwtUtil(secret, ttl);
        this.lbWebClient = lbWebClientBuilder.build();
        this.pdpCircuitBreaker = circuitBreakerRegistry.circuitBreaker("rbac-check");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        // 公开路由：登录/注册/健康检查，直接放行（不审计未认证流量）
        if (path.equals("/api/login") || path.equals("/api/register")
                || path.equals("/health") || path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        // 非 /api/** 也放行（避免误拦静态资源等）
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        // 1) 认证：校验 JWT，拿到 username
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            emitAudit(null, "auth:missing", method, path, extractResourceId(path), "DENY", 401);
            return unauthorized(exchange, "missing bearer token");
        }
        String username;
        try {
            username = jwtUtil.verify(auth.substring(7));
        } catch (JwtException e) {
            emitAudit(null, "auth:invalid", method, path, extractResourceId(path), "DENY", 401);
            return unauthorized(exchange, e.getMessage());
        }

        // 身份注入：把当前用户名透传给下游服务（审批单记录申请人、审计留痕等）
        ServerWebExchange authed = exchange.mutate().request(
                exchange.getRequest().mutate().header("X-User", username).build()
        ).build();

        // 2) 边缘鉴权（PEP）：仅对需要特定权限的路由委托 rbac 判定
        String required = mapPermission(path, method);
        Long resourceId = extractResourceId(path);
        if (required == null) {
            // 已登录即可访问（/api/me, /api/check, /api/audit 等）：记审计后放行
            emitAudit(username, method + " " + path, method, path, resourceId, "ALLOW", null);
            return chain.filter(authed);
        }

        return lbWebClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("http").host("rbac-service").path("/api/check")
                        .queryParam("user", username).queryParam("permission", required).build())
                .retrieve()
                .bodyToMono(Map.class)
                .transformDeferred(CircuitBreakerOperator.of(pdpCircuitBreaker))
                .flatMap(resp -> {
                    boolean allowed = Boolean.TRUE.equals(resp.get("allowed"));
                    if (allowed) {
                        emitAudit(username, required, method, path, resourceId, "ALLOW", null);
                        return chain.filter(authed);
                    }
                    emitAudit(username, required, method, path, resourceId, "DENY", 403);
                    return forbidden(exchange, "forbidden: requires " + required);
                })
                .onErrorResume(e -> {
                    emitAudit(username, required, method, path, resourceId, "DENY", 403);
                    return forbidden(exchange,
                            "forbidden: pdp degraded (circuit " + pdpCircuitBreaker.getState()
                                    + "): " + e.getClass().getSimpleName());
                });
    }

    /** 把 HTTP 路径 + 方法映射到所需权限（与 RBAC 的 /api/check 约定一致）。 */
    private String mapPermission(String path, String method) {
        String[] seg = path.split("/");
        if (seg.length < 3) {
            return null;
        }
        return switch (seg[2]) {
            case "roles" -> "GET".equals(method) ? "roles:read" : "roles:write";
            case "permissions" -> "permissions:read";
            case "users" -> "GET".equals(method) ? "users:read" : "users:write";
            case "customers" -> mapCustomerPermission(method);
            case "approvals" -> "customers:approve"; // 审批端点：仅审批人可访问
            case "audit" -> "audit:read";             // 审计日志：仅管理员可读
            default -> null; // me / check 仅需登录
        };
    }

    /** customers 域按 HTTP 方法细分权限：建/改/删 各成一档，便于角色差异化授权。 */
    private String mapCustomerPermission(String method) {
        return switch (method) {
            case "GET" -> "customers:read";
            case "POST" -> "customers:create";
            case "PUT" -> "customers:update";
            case "DELETE" -> "customers:delete";
            default -> null;
        };
    }

    /** 从路径中解析资源 id（如 /api/customers/123、/api/approvals/45/reject）。 */
    private Long extractResourceId(String path) {
        String[] seg = path.split("/");
        for (int i = 3; i < seg.length; i++) {
            if (seg[i].matches("\\d+")) {
                try {
                    return Long.parseLong(seg[i]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 异步、best-effort 发射审计事件到 audit-service（fail-open）。
     * 仅做 fire-and-forget，不阻塞主链路；审计服务不可用只记本地日志，不影响业务响应。
     */
    private void emitAudit(String actor, String action, String method, String path,
                           Long resourceId, String decision, Integer status) {
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("actor", actor == null ? "anonymous" : actor);
            body.put("action", action);
            body.put("method", method);
            body.put("path", path);
            body.put("resourceId", resourceId);   // 无资源时存 null
            body.put("decision", decision);
            body.put("status", status);           // 放行时未知最终状态码，存 null
            body.put("detail", "");
            lbWebClient.post()
                    .uri("lb://audit-service/api/audit")
                    .header(AUDIT_HEADER, AUDIT_HEADER_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .subscribe(
                            v -> {},
                            e -> log.warn("audit emit failed (ignored): {}", e.getMessage())
                    );
        } catch (Exception e) {
            log.warn("audit emit error (ignored): {}", e.getMessage());
        }
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return writeJson(exchange, HttpStatus.UNAUTHORIZED, message);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return writeJson(exchange, HttpStatus.FORBIDDEN, message);
    }
}
