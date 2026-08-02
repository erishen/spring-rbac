package com.example.rbac.customer.controller;

import com.example.rbac.customer.dto.CustomerDtos.CreateCustomerRequest;
import com.example.rbac.customer.dto.CustomerDtos.UpdateCustomerRequest;
import com.example.rbac.customer.model.Customer;
import com.example.rbac.customer.service.CustomerService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/customers")
    public Page<Customer> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return customerService.list(q, page, size);
    }

    @GetMapping("/customers/{id}")
    public Customer get(@PathVariable Long id) {
        return customerService.get(id);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Customer create(@RequestBody CreateCustomerRequest req) {
        return customerService.create(req);
    }

    @PutMapping("/customers/{id}")
    public Customer update(@PathVariable Long id, @RequestBody UpdateCustomerRequest req) {
        return customerService.update(id, req);
    }

    @DeleteMapping("/customers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        customerService.delete(id);
    }
}
