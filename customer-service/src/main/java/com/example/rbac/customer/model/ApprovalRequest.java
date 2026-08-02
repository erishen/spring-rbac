package com.example.rbac.customer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 审批请求（如「删除客户」需管理员批准才生效）。
 * 落库在 customer-service 的 H2，随 ddl-auto=create 自动建表。
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    public static final String TYPE_DELETE_CUSTOMER = "DELETE_CUSTOMER";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type;            // 审批类型，如 DELETE_CUSTOMER

    @Column(nullable = false)
    private Long targetId;         // 目标资源 id（客户 id）

    @Column
    private String targetName;     // 目标资源名称（冗余，便于列表展示）

    @Column(nullable = false)
    private String applicant;      // 申请人 username（来自网关透传的 X-User）

    @Column(nullable = false)
    private String status;         // PENDING / APPROVED / REJECTED

    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
    private String approver;       // 审批人 username

    @Column(length = 1000)
    private String decisionNote;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = STATUS_PENDING;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getApplicant() {
        return applicant;
    }

    public void setApplicant(String applicant) {
        this.applicant = applicant;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }
}
