package com.example.rbac.customer.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /** 支持服务发现（lb:// / 服务名）的 RestTemplate，供本服务查询 RBAC PDP。 */
    @Bean
    @LoadBalanced
    public RestTemplate lbRestTemplate() {
        return new RestTemplate();
    }
}
