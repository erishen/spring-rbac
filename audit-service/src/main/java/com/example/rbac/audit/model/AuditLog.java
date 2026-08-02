package com.example.rbac.audit.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 审计日志（append-only）：记录每一次经网关 PEP 裁决的请求。
 * 由网关在裁决后异步发射，本服务只负责落库与供管理员查询，不提供删除/修改。
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 轻量链路追踪 id：网关生成并透传的 X-Trace-Id，可串联同一请求的裁决与下游日志。 */
    @Column(length = 32)
    private String traceId;

    /** 操作人（网关从 JWT 解析并透传的 username）。 */
    @Column(nullable = false)
    private String actor;

    /** 动作：权限点（如 customers:delete）或 http 动作（如 GET /api/me）。 */
    @Column(nullable = false, length = 120)
    private String action;

    /** HTTP 方法。 */
    @Column(nullable = false, length = 10)
    private String method;

    /** 请求路径。 */
    @Column(nullable = false, length = 255)
    private String path;

    /** 资源 id（从路径中解析，如 /api/customers/123 -> 123），无则 null。 */
    private Long resourceId;

    /** 裁决结果：ALLOW / DENY。 */
    @Column(nullable = false, length = 10)
    private String decision;

    /** HTTP 状态码（成功 2xx / 拒绝 401/403），可为 null。 */
    private Integer status;

    /** 附加说明（可选）。 */
    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
