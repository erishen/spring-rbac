package com.example.rbac.auth.util;

/** 轻量 JWT 错误（替代引入 jjwt 依赖，避免与 Spring Boot 自带 jackson 版本冲突）。 */
public class JwtException extends RuntimeException {

    public JwtException(String message) {
        super(message);
    }
}
