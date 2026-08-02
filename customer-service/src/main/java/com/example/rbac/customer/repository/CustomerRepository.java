package com.example.rbac.customer.repository;

import com.example.rbac.customer.model.ApprovalRequest;
import com.example.rbac.customer.model.Customer;
import org.springframework.data.domain.Page;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /** 按 姓名/公司/电话/邮箱 模糊检索（不区分大小写），分页。 */
    @Query("SELECT c FROM Customer c WHERE " +
            "LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "LOWER(c.company) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "LOWER(c.phone) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<Customer> search(@Param("q") String q, Pageable pageable);

    /** 审批列表排序用：返回全部审批单（最新在前）。 */
    List<ApprovalRequest> findAllByOrderByCreatedAtDesc();
}
