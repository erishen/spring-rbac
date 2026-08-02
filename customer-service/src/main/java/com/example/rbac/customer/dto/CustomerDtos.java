package com.example.rbac.customer.dto;

/** 客户服务对外 DTO（record 简化）。 */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CreateCustomerRequest(
            String name,
            String company,
            String phone,
            String email,
            String status,
            String notes
    ) {
    }

    public record UpdateCustomerRequest(
            String name,
            String company,
            String phone,
            String email,
            String status,
            String notes
    ) {
    }
}
