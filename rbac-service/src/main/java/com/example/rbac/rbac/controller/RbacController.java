package com.example.rbac.rbac.controller;

import com.example.rbac.rbac.dto.RbacDtos.AssignPermissionRequest;
import com.example.rbac.rbac.dto.RbacDtos.AssignRoleRequest;
import com.example.rbac.rbac.dto.RbacDtos.CheckResponse;
import com.example.rbac.rbac.dto.RbacDtos.CreateRoleRequest;
import com.example.rbac.rbac.dto.RbacDtos.PermissionDto;
import com.example.rbac.rbac.dto.RbacDtos.RoleDto;
import com.example.rbac.rbac.dto.RbacDtos.UpdateRoleRequest;
import com.example.rbac.rbac.dto.RbacDtos.UserRoleView;
import com.example.rbac.rbac.service.RbacService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RbacController {

    private final RbacService rbacService;

    public RbacController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping("/roles")
    public List<RoleDto> listRoles() {
        return rbacService.listRoles();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleDto createRole(@RequestBody CreateRoleRequest req) {
        return rbacService.createRole(req);
    }

    @GetMapping("/roles/{id}")
    public RoleDto getRole(@PathVariable Long id) {
        return rbacService.getRole(id);
    }

    @PutMapping("/roles/{id}")
    public RoleDto updateRole(@PathVariable Long id, @RequestBody UpdateRoleRequest req) {
        return rbacService.updateRole(id, req);
    }

    @GetMapping("/permissions")
    public List<PermissionDto> listPermissions() {
        return rbacService.listPermissions();
    }

    @PostMapping("/users/{username}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignRoleToUser(@PathVariable String username,
                                                 @RequestBody AssignRoleRequest req) {
        rbacService.assignRoleToUser(username, req.roleId());
        return Map.of("username", username, "roleId", req.roleId());
    }

    @GetMapping("/users/{username}/roles")
    public List<UserRoleView> getUserRoles(@PathVariable String username) {
        return rbacService.getUserRoles(username);
    }

    @PostMapping("/roles/{id}/permissions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignPermission(@PathVariable Long id,
                                                 @RequestBody AssignPermissionRequest req) {
        rbacService.assignPermissionToRole(id, req.permissionId());
        return Map.of("roleId", id, "permissionId", req.permissionId());
    }

    @GetMapping("/check")
    public CheckResponse check(@RequestParam String user, @RequestParam String permission) {
        return rbacService.check(user, permission);
    }
}
