package com.example.rbac.customer;

import com.example.rbac.customer.service.CustomerService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableDiscoveryClient
public class CustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }

    /** 首次启动播种几条示例客户，便于直接演示 CRM 列表与权限门禁。 */
    @Bean
    CommandLineRunner seed(CustomerService customerService) {
        return args -> customerService.seedIfEmpty();
    }
}
