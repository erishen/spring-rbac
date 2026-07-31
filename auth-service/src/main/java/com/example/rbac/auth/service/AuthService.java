package com.example.rbac.auth.service;

import com.example.rbac.auth.exception.AuthFailedException;
import com.example.rbac.auth.exception.ConflictException;
import com.example.rbac.auth.model.User;
import com.example.rbac.auth.repository.UserRepository;
import com.example.rbac.auth.util.JwtUtil;
import com.example.rbac.auth.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository,
                       @Value("${app.jwt-secret}") String secret,
                       @Value("${app.jwt-ttl}") long ttlMillis) {
        this.userRepository = userRepository;
        this.jwtUtil = new JwtUtil(secret, ttlMillis);
    }

    public User register(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new IllegalArgumentException("用户名不能为空，密码至少 6 位");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("用户名已存在: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(PasswordUtil.hash(password));
        user.setCreatedAt(System.currentTimeMillis());
        return userRepository.save(user);
    }

    public String login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AuthFailedException("用户名或密码错误"));
        if (!PasswordUtil.matches(password, user.getPasswordHash())) {
            throw new AuthFailedException("用户名或密码错误");
        }
        return jwtUtil.generate(username);
    }

    /** 校验 JWT，返回其中的 username；失败抛 AuthFailedException(401)。 */
    public String verify(String token) {
        try {
            return jwtUtil.verify(token);
        } catch (RuntimeException e) {
            throw new AuthFailedException(e.getMessage());
        }
    }
}
