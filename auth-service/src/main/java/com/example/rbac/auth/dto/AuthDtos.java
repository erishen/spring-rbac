package com.example.rbac.auth.dto;

/** 认证服务对外 DTO（用 record 简化）。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(String username, String password) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record TokenResponse(String token) {
    }

    public record UserInfo(String username) {
    }
}
