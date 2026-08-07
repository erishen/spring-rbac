package com.example.rbac.gateway.filter;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthGlobalFilter.mapPermission（HTTP 路径+方法 -> 所需权限）的纯单元测试。
 *
 * mapPermission 是 private，这里用 ReflectionTestUtils 调用，零生产代码改动。
 * 构造 AuthGlobalFilter 所需的 WebClient.Builder / CircuitBreakerRegistry 传默认实例即可
 * （mapPermission 不依赖它们）。不启动 Spring 上下文，不触发 WebClient / 熔断器。
 */
class AuthGlobalFilterTest {

    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthGlobalFilter(
                "test-secret-test-secret-test-secret-1234567890",
                86400000L,
                WebClient.builder(),
                CircuitBreakerRegistry.ofDefaults());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/roles, GET, roles:read",
            "/api/roles, POST, roles:write",
            "/api/roles/5, PUT, roles:write",
            "/api/roles/5, DELETE, roles:write",
            "/api/permissions, GET, permissions:read",
            "/api/permissions, DELETE, permissions:read",
            "/api/users, GET, users:read",
            "/api/users, POST, users:write",
            "/api/users/abc, PUT, users:write",
            "/api/customers, GET, customers:read",
            "/api/customers, POST, customers:create",
            "/api/customers/1, PUT, customers:update",
            "/api/customers/1, DELETE, customers:delete",
            "/api/approvals, GET, customers:approve",
            "/api/approvals/45/reject, POST, customers:approve",
            "/api/audit, GET, audit:read",
    })
    void mapPermissionReturnsExpected(String path, String method, String expected) {
        assertThat(invoke(path, method)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/me, GET",
            "/api/check, GET",
            "/api, GET",
            "/, GET",
            "/api/customers, PATCH",
    })
    void mapPermissionReturnsNullForLoginOnlyOrUnknown(String path, String method) {
        // 仅需登录（me/check）、路径过短（/api、/）、或未覆盖的方法（PATCH）-> null
        assertThat(invoke(path, method)).isNull();
    }

    private String invoke(String path, String method) {
        return ReflectionTestUtils.invokeMethod(filter, "mapPermission", path, method);
    }
}
