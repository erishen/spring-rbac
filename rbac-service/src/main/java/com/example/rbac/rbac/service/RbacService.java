package com.example.rbac.rbac.service;

import com.example.rbac.rbac.dto.RbacDtos.AssignPermissionRequest;
import com.example.rbac.rbac.dto.RbacDtos.AssignRoleRequest;
import com.example.rbac.rbac.dto.RbacDtos.CheckResponse;
import com.example.rbac.rbac.dto.RbacDtos.CreateRoleRequest;
import com.example.rbac.rbac.dto.RbacDtos.PermissionDto;
import com.example.rbac.rbac.dto.RbacDtos.RoleDto;
import com.example.rbac.rbac.dto.RbacDtos.UpdateRoleRequest;
import com.example.rbac.rbac.dto.RbacDtos.UserRoleView;
import com.example.rbac.rbac.exception.ConflictException;
import com.example.rbac.rbac.exception.NotFoundException;
import com.example.rbac.rbac.model.Permission;
import com.example.rbac.rbac.model.Role;
import com.example.rbac.rbac.model.RolePermission;
import com.example.rbac.rbac.model.UserRole;
import com.example.rbac.rbac.repository.PermissionRepository;
import com.example.rbac.rbac.repository.RolePermissionRepository;
import com.example.rbac.rbac.repository.RoleRepository;
import com.example.rbac.rbac.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RbacService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public RbacService(RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       UserRoleRepository userRoleRepository,
                       RolePermissionRepository rolePermissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    /** 幂等播种：角色 / 权限 / 继承关系 / admin 用户挂载。 */
    public void seedIfEmpty() {
        if (permissionRepository.findByName("roles:read").isPresent()) {
            return;
        }
        Permission pUsersRead = savePerm("users:read");
        Permission pUsersWrite = savePerm("users:write");
        Permission pRolesRead = savePerm("roles:read");
        Permission pRolesWrite = savePerm("roles:write");
        Permission pPermsRead = savePerm("permissions:read");

        Role admin = saveRole("admin", null);
        Role user = saveRole("user", null);
        Role viewer = saveRole("viewer", user.getId()); // viewer 继承 user

        assignPerm(admin, pUsersRead);
        assignPerm(admin, pUsersWrite);
        assignPerm(admin, pRolesRead);
        assignPerm(admin, pRolesWrite);
        assignPerm(admin, pPermsRead);

        assignPerm(user, pUsersRead);
        assignPerm(user, pRolesRead);
        assignPerm(user, pPermsRead);

        assignPerm(viewer, pUsersRead);
        assignPerm(viewer, pRolesRead);

        assignRole("admin", admin.getId()); // admin 用户 -> admin 角色（拥有全部权限）

        System.out.println("[rbac] seeded roles/permissions + inheritance (viewer -> user) + admin assignment");
    }

    public List<RoleDto> listRoles() {
        return roleRepository.findAll().stream()
                .map(r -> new RoleDto(r.getId(), r.getName(), r.getParentId()))
                .toList();
    }

    public List<PermissionDto> listPermissions() {
        return permissionRepository.findAll().stream()
                .map(p -> new PermissionDto(p.getId(), p.getName()))
                .toList();
    }

    public RoleDto getRole(Long id) {
        Role r = roleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("role not found: " + id));
        return new RoleDto(r.getId(), r.getName(), r.getParentId());
    }

    public RoleDto createRole(CreateRoleRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        if (roleRepository.findByName(req.name()).isPresent()) {
            throw new ConflictException("role exists: " + req.name());
        }
        if (req.parentId() != null && roleRepository.findById(req.parentId()).isEmpty()) {
            throw new NotFoundException("parent role not found: " + req.parentId());
        }
        Role r = saveRole(req.name(), req.parentId());
        return new RoleDto(r.getId(), r.getName(), r.getParentId());
    }

    public RoleDto updateRole(Long id, UpdateRoleRequest req) {
        Role r = roleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("role not found: " + id));
        if (req.name() != null && !req.name().isBlank()) {
            r.setName(req.name());
        }
        if (req.parentId() != null && roleRepository.findById(req.parentId()).isEmpty()) {
            throw new NotFoundException("parent role not found: " + req.parentId());
        }
        r.setParentId(req.parentId());
        Role saved = roleRepository.save(r);
        return new RoleDto(saved.getId(), saved.getName(), saved.getParentId());
    }

    public void assignRoleToUser(String username, Long roleId) {
        if (roleRepository.findById(roleId).isEmpty()) {
            throw new NotFoundException("role not found: " + roleId);
        }
        assignRole(username, roleId);
    }

    public List<UserRoleView> getUserRoles(String username) {
        return userRoleRepository.findByUsername(username).stream().map(ur -> {
            Role r = roleRepository.findById(ur.getRoleId()).orElse(null);
            return new UserRoleView(ur.getRoleId(), r == null ? null : r.getName());
        }).toList();
    }

    public void assignPermissionToRole(Long roleId, Long permissionId) {
        if (roleRepository.findById(roleId).isEmpty()) {
            throw new NotFoundException("role not found: " + roleId);
        }
        if (permissionRepository.findById(permissionId).isEmpty()) {
            throw new NotFoundException("permission not found: " + permissionId);
        }
        assignPerm2(roleId, permissionId);
    }

    /** 解析某用户的全部有效权限（含角色继承的递归展开）。 */
    public Set<String> resolveEffectivePermissions(String username) {
        Set<Long> visited = new HashSet<>();
        Set<String> perms = new HashSet<>();
        Queue<Long> queue = new LinkedList<>();
        userRoleRepository.findByUsername(username).forEach(ur -> queue.add(ur.getRoleId()));

        while (!queue.isEmpty()) {
            Long roleId = queue.poll();
            if (roleId == null || !visited.add(roleId)) {
                continue;
            }
            rolePermissionRepository.findByRoleId(roleId).forEach(rp ->
                    permissionRepository.findById(rp.getPermissionId())
                            .ifPresent(p -> perms.add(p.getName())));
            roleRepository.findById(roleId).ifPresent(role -> {
                if (role.getParentId() != null) {
                    queue.add(role.getParentId());
                }
            });
        }
        return perms;
    }

    public CheckResponse check(String username, String permission) {
        return new CheckResponse(resolveEffectivePermissions(username).contains(permission));
    }

    private Permission savePerm(String name) {
        Permission p = new Permission();
        p.setName(name);
        return permissionRepository.save(p);
    }

    private Role saveRole(String name, Long parentId) {
        Role r = new Role();
        r.setName(name);
        r.setParentId(parentId);
        return roleRepository.save(r);
    }

    private void assignPerm(Role role, Permission permission) {
        assignPerm2(role.getId(), permission.getId());
    }

    private void assignPerm2(Long roleId, Long permissionId) {
        if (!rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permissionId)) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permissionId);
            rolePermissionRepository.save(rp);
        }
    }

    private void assignRole(String username, Long roleId) {
        if (!userRoleRepository.existsByUsernameAndRoleId(username, roleId)) {
            UserRole ur = new UserRole();
            ur.setUsername(username);
            ur.setRoleId(roleId);
            userRoleRepository.save(ur);
        }
    }
}
