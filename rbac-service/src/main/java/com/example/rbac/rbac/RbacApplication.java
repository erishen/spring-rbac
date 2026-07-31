package com.example.rbac.rbac;

import com.example.rbac.rbac.service.RbacService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableDiscoveryClient
public class RbacApplication {

    public static void main(String[] args) {
        SpringApplication.run(RbacApplication.class, args);
    }

    /** 首次启动播种角色/权限/继承关系，并把 admin 用户挂到 admin 角色。 */
    @Bean
    CommandLineRunner seed(RbacService rbacService) {
        return args -> rbacService.seedIfEmpty();
    }
}
