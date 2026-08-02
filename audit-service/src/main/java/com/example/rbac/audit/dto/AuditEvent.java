package com.example.rbac.audit.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 网关发射过来的审计事件（请求体）。所有字段来自网关 PEP 裁决结果。 */
public class AuditEvent {

    public final String actor;
    public final String action;
    public final String method;
    public final String path;
    public final Long resourceId;
    public final String decision;
    public final Integer status;
    public final String detail;

    @JsonCreator
    public AuditEvent(
            @JsonProperty("actor") String actor,
            @JsonProperty("action") String action,
            @JsonProperty("method") String method,
            @JsonProperty("path") String path,
            @JsonProperty("resourceId") Long resourceId,
            @JsonProperty("decision") String decision,
            @JsonProperty("status") Integer status,
            @JsonProperty("detail") String detail) {
        this.actor = actor;
        this.action = action;
        this.method = method;
        this.path = path;
        this.resourceId = resourceId;
        this.decision = decision;
        this.status = status;
        this.detail = detail;
    }
}
