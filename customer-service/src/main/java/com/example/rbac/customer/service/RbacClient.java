package com.example.rbac.customer.service;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 轻量 RBAC PDP 客户端：本服务（CRM）在需要判断「操作人是否为审批人」时，
 * 直接查询 RBAC 的 /api/check 实时裁决，与网关 PEP 同源，保证「角色驱动」一致。
 *
 * 网络失败 / 异常时保守返回 false（即视为非审批人、走审批流），不阻断正常业务。
 */
@Component
public class RbacClient {

    private final RestTemplate lbRestTemplate;

    public RbacClient(RestTemplate lbRestTemplate) {
        this.lbRestTemplate = lbRestTemplate;
    }

    /** actor 是否拥有指定权限（如 customers:approve）。 */
    public boolean hasPermission(String user, String permission) {
        if (user == null || user.isBlank()) {
            return false;
        }
        try {
            String url = "http://rbac-service/api/check?user={user}&permission={perm}";
            Map<?, ?> resp = lbRestTemplate.getForObject(url, Map.class, user, permission);
            if (resp == null) {
                return false;
            }
            return Boolean.TRUE.equals(resp.get("allowed"));
        } catch (Exception e) {
            // 失败兜底：保守走审批流，避免越权直接删除
            return false;
        }
    }
}
