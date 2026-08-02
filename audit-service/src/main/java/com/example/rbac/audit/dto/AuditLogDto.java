package com.example.rbac.audit.dto;

/** 审计日志对外视图（管理员读取）。 */
public class AuditLogDto {

    public final Long id;
    public final String actor;
    public final String action;
    public final String method;
    public final String path;
    public final Long resourceId;
    public final String decision;
    public final Integer status;
    public final String detail;
    public final String createdAt;

    public AuditLogDto(Long id, String actor, String action, String method,
                       String path, Long resourceId, String decision,
                       Integer status, String detail, String createdAt) {
        this.id = id;
        this.actor = actor;
        this.action = action;
        this.method = method;
        this.path = path;
        this.resourceId = resourceId;
        this.decision = decision;
        this.status = status;
        this.detail = detail;
        this.createdAt = createdAt;
    }
}
