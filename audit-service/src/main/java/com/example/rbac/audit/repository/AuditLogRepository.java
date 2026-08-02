package com.example.rbac.audit.repository;

import com.example.rbac.audit.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {

    /** 分页查询审计记录（排序由调用方的 Pageable 指定，默认按时间倒序）。 */
    @Query("SELECT a FROM AuditLog a")
    Page<AuditLog> findPaged(Pageable pageable);

    /** 今日审计记录总数。 */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :start")
    long countSince(@Param("start") LocalDateTime start);

    /** 今日被拒绝（DENY）的记录数。 */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :start AND a.decision = 'DENY'")
    long countDeniedSince(@Param("start") LocalDateTime start);

    /** 今日去重操作人数（排除匿名/认证失败标记，仅统计真实账号）。 */
    @Query("SELECT COUNT(DISTINCT a.actor) FROM AuditLog a " +
           "WHERE a.createdAt >= :start AND a.actor <> 'anonymous'")
    long countActiveUsersSince(@Param("start") LocalDateTime start);
}
