package com.example.rbac.audit.dto;

import java.util.List;

/** 审计日志分页响应：内容 + 分页元数据（供前端翻页控件使用）。 */
public class AuditPageDto {
    public List<AuditLogDto> content;
    public long total;
    public int page;
    public int size;
    public int totalPages;

    public AuditPageDto(List<AuditLogDto> content, long total, int page, int size, int totalPages) {
        this.content = content;
        this.total = total;
        this.page = page;
        this.size = size;
        this.totalPages = totalPages;
    }
}
