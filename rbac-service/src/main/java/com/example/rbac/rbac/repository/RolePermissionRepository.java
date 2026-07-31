package com.example.rbac.rbac.repository;

import com.example.rbac.rbac.model.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByRoleId(Long roleId);

    boolean existsByRoleIdAndPermissionId(Long roleId, Long permissionId);
}
