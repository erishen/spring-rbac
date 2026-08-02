package com.example.rbac.customer.dto;

/** 删除操作的返回：deleted=true 表示管理员直接删除生效；false 表示已提交审批流（approvalId 待审单）。 */
public record DeleteResult(boolean deleted, Long approvalId) {
}
