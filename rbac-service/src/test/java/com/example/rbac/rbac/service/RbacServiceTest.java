package com.example.rbac.rbac.service;

import com.example.rbac.rbac.model.Permission;
import com.example.rbac.rbac.model.Role;
import com.example.rbac.rbac.model.RolePermission;
import com.example.rbac.rbac.model.UserRole;
import com.example.rbac.rbac.repository.PermissionRepository;
import com.example.rbac.rbac.repository.RolePermissionRepository;
import com.example.rbac.rbac.repository.RoleRepository;
import com.example.rbac.rbac.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RbacService 单元测试：用 @DataJpaTest 切片 + 真实 H2 内存库，避免对 4 个 JPA 仓库
 * 逐个 mock 的脆弱性，直接验证 BFS 有效权限解析与角色继承逻辑。
 *
 * 注意：seedIfEmpty() 在生产中由 RbacApplication 的 CommandLineRunner 触发，@DataJpaTest
 * 不会加载它，故在每个测试前手动播种三档角色（admin/editor/viewer）。
 */
@DataJpaTest
@Import(RbacService.class)
class RbacServiceTest {

    @Autowired RbacService rbacService;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;

    @BeforeEach
    void seed() {
        rbacService.seedIfEmpty();
    }

    @Test
    void adminHasAllElevenPermissions() {
        Set<String> perms = rbacService.resolveEffectivePermissions("admin");
        assertThat(perms).containsExactlyInAnyOrder(
                "users:read", "users:write",
                "roles:read", "roles:write",
                "permissions:read",
                "customers:read", "customers:create", "customers:update",
                "customers:delete", "customers:approve",
                "audit:read");
        assertThat(perms).hasSize(11);
    }

    @Test
    void viewerIsReadOnly() {
        Set<String> perms = rbacService.resolveEffectivePermissions("viewer");
        assertThat(perms).containsExactlyInAnyOrder(
                "users:read", "roles:read", "permissions:read", "customers:read");
        assertThat(perms).doesNotContain(
                "users:write", "roles:write", "customers:create", "customers:update",
                "customers:delete", "customers:approve", "audit:read");
    }

    @Test
    void editorCanEditButCannotApproveOrAudit() {
        // "user" 账号挂载 editor 角色：可增/改/发起删除，但不能审批与看审计
        Set<String> perms = rbacService.resolveEffectivePermissions("user");
        assertThat(perms).contains("customers:create", "customers:update", "customers:delete");
        assertThat(perms).doesNotContain("customers:approve", "audit:read");
        assertThat(perms).hasSize(7);
    }

    @Test
    void checkGrantsAndDenies() {
        assertThat(rbacService.check("admin", "audit:read").allowed()).isTrue();
        assertThat(rbacService.check("viewer", "audit:read").allowed()).isFalse();
        assertThat(rbacService.check("user", "customers:create").allowed()).isTrue();
        assertThat(rbacService.check("viewer", "customers:create").allowed()).isFalse();
    }

    @Test
    void unknownUserHasNoPermissions() {
        assertThat(rbacService.resolveEffectivePermissions("nobody")).isEmpty();
        assertThat(rbacService.check("nobody", "users:read").allowed()).isFalse();
    }

    @Test
    void roleInheritancePropagatesParentPermissions() {
        // 种子角色 parentId 均为 null，本测试专门覆盖继承分支：
        // 新建子角色 child-of-admin 继承 admin，子角色自身无直接权限。
        Role admin = roleRepository.findByName("admin").orElseThrow();
        Role child = new Role();
        child.setName("child-of-admin");
        child.setParentId(admin.getId());
        roleRepository.save(child);

        // 仅给 admin 增加一个新权限
        Permission p = new Permission();
        p.setName("custom:inherited");
        permissionRepository.save(p);
        RolePermission rp = new RolePermission();
        rp.setRoleId(admin.getId());
        rp.setPermissionId(p.getId());
        rolePermissionRepository.save(rp);

        UserRole ur = new UserRole();
        ur.setUsername("childuser");
        ur.setRoleId(child.getId());
        userRoleRepository.save(ur);

        // 子用户通过继承获得 admin 的全部权限，含新增的 custom:inherited
        Set<String> perms = rbacService.resolveEffectivePermissions("childuser");
        assertThat(perms).contains("custom:inherited", "roles:read", "audit:read");
    }

    @Test
    void roleInheritanceCycleIsSafe() {
        // 构造两个互为父角色的角色，验证 visited 集合能终止 BFS（不死循环）
        Role a = new Role();
        a.setName("cycle-a");
        roleRepository.save(a);
        Role b = new Role();
        b.setName("cycle-b");
        roleRepository.save(b);

        a.setParentId(b.getId());
        roleRepository.save(a);
        b.setParentId(a.getId());
        roleRepository.save(b);

        Permission p = new Permission();
        p.setName("custom:cycle");
        permissionRepository.save(p);
        RolePermission rp = new RolePermission();
        rp.setRoleId(b.getId());
        rp.setPermissionId(p.getId());
        rolePermissionRepository.save(rp);

        UserRole ur = new UserRole();
        ur.setUsername("cycleuser");
        ur.setRoleId(a.getId());
        userRoleRepository.save(ur);

        // 若未防环，此处会栈溢出；正常返回即证明 visited 生效
        Set<String> perms = rbacService.resolveEffectivePermissions("cycleuser");
        assertThat(perms).contains("custom:cycle");
    }
}
