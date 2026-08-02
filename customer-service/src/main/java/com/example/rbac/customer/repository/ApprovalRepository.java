package com.example.rbac.customer.repository;

import com.example.rbac.customer.model.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ApprovalRepository extends JpaRepository<ApprovalRequest, Long> {

    /** 按状态列出审批单（最新在前）。 */
    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(String status);

    /** 查找指定目标的指定类型待审单（用于幂等去重）。 */
    List<ApprovalRequest> findByTargetIdAndTypeAndStatus(Long targetId, String type, String status);

    /** 全部审批单（最新在前）。 */
    List<ApprovalRequest> findAllByOrderByCreatedAtDesc();
}
