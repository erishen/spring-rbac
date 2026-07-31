package com.example.rbac.auth.controller;

import com.example.rbac.auth.dto.AuthDtos.LoginRequest;
import com.example.rbac.auth.dto.AuthDtos.RegisterRequest;
import com.example.rbac.auth.dto.AuthDtos.TokenResponse;
import com.example.rbac.auth.dto.AuthDtos.UserInfo;
import com.example.rbac.auth.exception.AuthFailedException;
import com.example.rbac.auth.model.User;
import com.example.rbac.auth.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest req) {
        User user = authService.register(req.username(), req.password());
        return Map.of("id", user.getId(), "username", user.getUsername());
    }

    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginRequest req) {
        return new TokenResponse(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public UserInfo me(@RequestHeader("Authorization") String authorization) {
        String username = authService.verify(extractToken(authorization));
        return new UserInfo(username);
    }

    private String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthFailedException("缺少 Bearer Token");
        }
        return authorization.substring(7);
    }
}
