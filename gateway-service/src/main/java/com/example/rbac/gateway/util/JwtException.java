package com.example.rbac.gateway.util;

/** 与 auth 服务同构的轻量 JWT 错误。 */
public class JwtException extends RuntimeException {

    public JwtException(String message) {
        super(message);
    }
}
