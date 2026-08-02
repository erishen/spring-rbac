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
import org.springframework.transaction.annotation.Transactional;

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
        // CRM 业务域权限：拆成 读 / 建 / 改 / 删 四档，使不同角色能体现不同权限
        Permission pCustRead = savePerm("customers:read");
        Permission pCustCreate = savePerm("customers:create");
        Permission pCustUpdate = savePerm("customers:update");
        Permission pCustDelete = savePerm("customers:delete");     // 语义=可发起删除申请
        Permission pCustApprove = savePerm("customers:approve");   // 审批删除申请（仅 admin）
        Permission pAuditRead = savePerm("audit:read");            // 查看跨服务审计日志（仅 admin）

        Role admin = saveRole("admin", null);
        Role editor = saveRole("editor", null); // 可增改、可申请删除(需 admin 审批)
        Role viewer = saveRole("viewer", null);  // 纯只读

        // admin：全权（含 CRM 增/改/删）
        assignPerm(admin, pUsersRead);
        assignPerm(admin, pUsersWrite);
        assignPerm(admin, pRolesRead);
        assignPerm(admin, pRolesWrite);
        assignPerm(admin, pPermsRead);
        assignPerm(admin, pCustRead);
        assignPerm(admin, pCustCreate);
        assignPerm(admin, pCustUpdate);
        assignPerm(admin, pCustDelete);
        assignPerm(admin, pCustApprove);
        assignPerm(admin, pAuditRead);

        // editor：可浏览 + 增 + 改 + 发起删除申请（删除需 admin 审批才生效），保留 RBAC 面板只读
        assignPerm(editor, pUsersRead);
        assignPerm(editor, pRolesRead);
        assignPerm(editor, pPermsRead);
        assignPerm(editor, pCustRead);
        assignPerm(editor, pCustCreate);
        assignPerm(editor, pCustUpdate);
        assignPerm(editor, pCustDelete);

        // viewer：仅只读（含 CRM 只读）
        assignPerm(viewer, pUsersRead);
        assignPerm(viewer, pRolesRead);
        assignPerm(viewer, pPermsRead);
        assignPerm(viewer, pCustRead);

        assignRole("admin", admin.getId());   // admin 用户 -> admin 角色（全权）
        assignRole("user", editor.getId());   // user 用户 -> editor 角色（可编辑不可删）
        assignRole("viewer", viewer.getId()); // viewer 用户 -> viewer 角色（只读）

        System.out.println("[rbac] seeded 3-tier CRM roles: admin(全权+审批)/editor(可增改+可申请删除)/viewer(只读) + RBAC perms");
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

    @Transactional
    public void revokeRoleFromUser(String username, Long roleId) {
        if (roleRepository.findById(roleId).isEmpty()) {
            throw new NotFoundException("role not found: " + roleId);
        }
        if (!userRoleRepository.existsByUsernameAndRoleId(username, roleId)) {
            throw new NotFoundException("user role not found: " + username + " / " + roleId);
        }
        userRoleRepository.deleteByUsernameAndRoleId(username, roleId);
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
