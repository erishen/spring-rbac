package com.example.rbac.customer.service;

import com.example.rbac.customer.exception.NotFoundException;
import com.example.rbac.customer.model.ApprovalRequest;
import com.example.rbac.customer.model.Customer;
import com.example.rbac.customer.repository.ApprovalRepository;
import com.example.rbac.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepository;
    private final CustomerRepository customerRepository;

    public ApprovalService(ApprovalRepository approvalRepository, CustomerRepository customerRepository) {
        this.approvalRepository = approvalRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * 发起「删除客户」审批：幂等——同一客户已存在 PENDING 删除单则直接返回，避免重复提交。
     * 审批通过前不会真正删除客户数据。
     */
    @Transactional
    public ApprovalRequest requestDelete(Long customerId, String applicant) {
        Customer c = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("customer not found: " + customerId));
        List<ApprovalRequest> existing = approvalRepository.findByTargetIdAndTypeAndStatus(
                customerId, ApprovalRequest.TYPE_DELETE_CUSTOMER, ApprovalRequest.STATUS_PENDING);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ApprovalRequest req = new ApprovalRequest();
        req.setType(ApprovalRequest.TYPE_DELETE_CUSTOMER);
        req.setTargetId(customerId);
        req.setTargetName(c.getName());
        req.setApplicant(applicant);
        req.setStatus(ApprovalRequest.STATUS_PENDING);
        return approvalRepository.save(req);
    }

    /** 列出审批单：status 为空列出全部（最新在前），否则按状态过滤。 */
    public List<ApprovalRequest> list(String status) {
        if (status == null || status.isBlank()) {
            return approvalRepository.findAllByOrderByCreatedAtDesc();
        }
        return approvalRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * 通过审批：执行真实删除（审批人无需再持 customers:delete，由后端内部调用）。
     * 客户可能已被他人删掉，静默忽略。
     */
    @Transactional
    public ApprovalRequest approve(Long id, String approver) {
        ApprovalRequest req = approvalRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("approval not found: " + id));
        if (!ApprovalRequest.STATUS_PENDING.equals(req.getStatus())) {
            throw new IllegalArgumentException("approval is not pending: " + req.getStatus());
        }
        req.setStatus(ApprovalRequest.STATUS_APPROVED);
        req.setApprover(approver);
        req.setDecidedAt(LocalDateTime.now());
        approvalRepository.save(req);
        if (customerRepository.existsById(req.getTargetId())) {
            customerRepository.deleteById(req.getTargetId());
        }
        return req;
    }

    /** 驳回审批。 */
    @Transactional
    public ApprovalRequest reject(Long id, String approver, String note) {
        ApprovalRequest req = approvalRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("approval not found: " + id));
        if (!ApprovalRequest.STATUS_PENDING.equals(req.getStatus())) {
            throw new IllegalArgumentException("approval is not pending: " + req.getStatus());
        }
        req.setStatus(ApprovalRequest.STATUS_REJECTED);
        req.setApprover(approver);
        req.setDecisionNote(note);
        req.setDecidedAt(LocalDateTime.now());
        return approvalRepository.save(req);
    }
}
