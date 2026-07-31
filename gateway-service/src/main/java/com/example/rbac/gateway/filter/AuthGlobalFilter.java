package com.example.rbac.gateway.filter;

import com.example.rbac.gateway.util.JwtException;
import com.example.rbac.gateway.util.JwtUtil;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
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
 *  3. 通过鉴权后交还给 Gateway，由其按 lb:// 路由转发到 auth / rbac 内部服务。
 * 内部服务只注册在 Eureka，互联网只能打到网关这一道门。
 */
@Component
@Order(-1)
public class AuthGlobalFilter implements GlobalFilter {

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

        // 公开路由：登录/注册/健康检查，直接放行
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
            return unauthorized(exchange, "missing bearer token");
        }
        String username;
        try {
            username = jwtUtil.verify(auth.substring(7));
        } catch (JwtException e) {
            return unauthorized(exchange, e.getMessage());
        }

        // 2) 边缘鉴权（PEP）：仅对需要特定权限的路由委托 rbac 判定
        String required = mapPermission(path, method);
        if (required == null) {
            return chain.filter(exchange); // 已登录即可（/api/me, /api/check）
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
                        return chain.filter(exchange);
                    }
                    return forbidden(exchange, "forbidden: requires " + required);
                })
                .onErrorResume(e -> forbidden(exchange,
                        "forbidden: pdp degraded (circuit " + pdpCircuitBreaker.getState()
                                + "): " + e.getClass().getSimpleName()));
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
            default -> null; // me / check 仅需登录
        };
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
