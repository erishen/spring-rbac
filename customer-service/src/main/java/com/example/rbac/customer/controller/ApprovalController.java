package com.example.rbac.customer.controller;

import com.example.rbac.customer.model.ApprovalRequest;
import com.example.rbac.customer.service.ApprovalService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /** 列出审批单（需 customers:approve，由网关 PEP 把关）。 */
    @GetMapping("/approvals")
    public List<ApprovalRequest> list(@RequestParam(required = false) String status) {
        return approvalService.list(status);
    }

    /** 通过审批：执行真实删除。操作人来自网关透传的 X-User。 */
    @PostMapping("/approvals/{id}/approve")
    @ResponseStatus(HttpStatus.OK)
    public ApprovalRequest approve(
            @PathVariable Long id,
            @RequestHeader(value = "X-User", required = false) String approver) {
        return approvalService.approve(id, approver);
    }

    /** 驳回审批。 */
    @PostMapping("/approvals/{id}/reject")
    @ResponseStatus(HttpStatus.OK)
    public ApprovalRequest reject(
            @PathVariable Long id,
            @RequestHeader(value = "X-User", required = false) String approver,
            @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return approvalService.reject(id, approver, note);
    }
}
