package com.example.rbac.rbac.repository;

import com.example.rbac.rbac.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    List<UserRole> findByUsername(String username);

    boolean existsByUsernameAndRoleId(String username, Long roleId);
}
