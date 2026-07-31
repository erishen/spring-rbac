package com.example.rbac.rbac.dto;

/** RBAC 服务对外 DTO（record 简化）。 */
public final class RbacDtos {

    private RbacDtos() {
    }

    public record RoleDto(Long id, String name, Long parentId) {
    }

    public record PermissionDto(Long id, String name) {
    }

    public record CreateRoleRequest(String name, Long parentId) {
    }

    public record UpdateRoleRequest(String name, Long parentId) {
    }

    public record AssignRoleRequest(Long roleId) {
    }

    public record AssignPermissionRequest(Long permissionId) {
    }

    public record CheckResponse(boolean allowed) {
    }

    public record UserRoleView(Long roleId, String roleName) {
    }
}
