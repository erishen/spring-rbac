package com.example.rbac.auth;

import com.example.rbac.auth.service.AuthService;
import com.example.rbac.auth.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableDiscoveryClient
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }

    /** 首次启动播种 admin 账号（ddl-auto=create 每次都是空库）。 */
    @Bean
    CommandLineRunner seed(UserRepository userRepository, AuthService authService) {
        return args -> {
            if (userRepository.findByUsername("admin").isEmpty()) {
                authService.register("admin", "admin123");
                System.out.println("[auth] seeded admin / admin123");
            }
        };
    }
}
