package com.example.rbac.audit.controller;

import com.example.rbac.audit.dto.AuditEvent;
import com.example.rbac.audit.dto.AuditLogDto;
import com.example.rbac.audit.dto.AuditPageDto;
import com.example.rbac.audit.dto.AuditStatsDto;
import com.example.rbac.audit.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AuditController {

    /** 网关内部发射审计事件时携带的私有头；缺失或非法的写入请求一律拒绝，防伪造。 */
    private static final String INTERNAL_HEADER = "X-Internal-Audit";
    private static final String INTERNAL_VALUE = "gateway";

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 写入审计事件。仅允许网关经服务发现（lb://audit-service）直连调用，
     * 且必须携带私有内部头；对外网关路由 /api/audit/** 仅放行 GET，外部无法写入。
     */
    @PostMapping("/audit")
    @ResponseStatus(HttpStatus.OK)
    public void append(@RequestHeader(value = INTERNAL_HEADER, required = false) String marker,
                       @RequestBody AuditEvent event) {
        if (!INTERNAL_VALUE.equals(marker)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audit write not allowed");
        }
        auditService.record(event);
    }

    /** 今日审计概览（统计卡用，需 audit:read，由网关 PEP 把关）。 */
    @GetMapping("/audit/stats")
    public AuditStatsDto stats() {
        return auditService.stats();
    }

    /** 分页查询审计记录（需 audit:read，由网关 PEP 把关；支持异常筛选）。 */
    @GetMapping("/audit")
    public AuditPageDto list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestParam(required = false) String decision,
                             @RequestParam(defaultValue = "false") boolean onlyDelete) {
        Page<AuditLogDto> p = auditService.paged(page, size, decision, onlyDelete);
        return new AuditPageDto(
                p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize(), p.getTotalPages());
    }
}
