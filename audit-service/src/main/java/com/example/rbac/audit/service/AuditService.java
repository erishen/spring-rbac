package com.example.rbac.audit.service;

import com.example.rbac.audit.dto.AuditEvent;
import com.example.rbac.audit.dto.AuditLogDto;
import com.example.rbac.audit.dto.AuditStatsDto;
import com.example.rbac.audit.model.AuditLog;
import com.example.rbac.audit.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** 落库一条审计事件（append-only，由网关异步发射）。 */
    @Transactional
    public void record(AuditEvent e) {
        AuditLog log = new AuditLog();
        log.setActor(e.actor);
        log.setAction(e.action);
        log.setMethod(e.method);
        log.setPath(e.path);
        log.setResourceId(e.resourceId);
        log.setDecision(e.decision);
        log.setStatus(e.status);
        log.setDetail(e.detail);
        repository.save(log);
    }

    /** 单条实体转 DTO（字段映射集中处理）。 */
    private AuditLogDto toDto(AuditLog a) {
        return new AuditLogDto(
                a.getId(),
                a.getActor(),
                a.getAction(),
                a.getMethod(),
                a.getPath(),
                a.getResourceId(),
                a.getDecision(),
                a.getStatus(),
                a.getDetail(),
                a.getCreatedAt() == null ? null : a.getCreatedAt().toString()
        );
    }

    /** 构建过滤条件：按裁决结果精确匹配；onlyDelete 匹配动作含 delete（不区分大小写）。 */
    private Specification<AuditLog> buildSpec(String decision, boolean onlyDelete) {
        Specification<AuditLog> spec = Specification.where(null);
        if (decision != null && !decision.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("decision"), decision));
        }
        if (onlyDelete) {
            spec = spec.and((root, q, cb) ->
                    cb.like(cb.lower(root.get("action")), "%delete%"));
        }
        return spec;
    }

    /** 分页查询审计记录（按时间倒序，其次 id 倒序），支持异常筛选。 */
    public Page<AuditLogDto> paged(int page, int size, String decision, boolean onlyDelete) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, 100));
        Pageable pg = PageRequest.of(p, s,
                Sort.by("createdAt").descending().and(Sort.by("id").descending()));
        return repository.findAll(buildSpec(decision, onlyDelete), pg).map(this::toDto);
    }

    /** 今日审计概览：总操作数 / 被拒数 / 活跃用户数（排除匿名）。 */
    public AuditStatsDto stats() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        return new AuditStatsDto(
                repository.countSince(start),
                repository.countDeniedSince(start),
                repository.countActiveUsersSince(start)
        );
    }
}
