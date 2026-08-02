package com.example.rbac.audit.dto;

/** 审计今日概览：供监控视图顶部统计卡使用。 */
public class AuditStatsDto {
    /** 今日审计记录总数。 */
    public long todayTotal;
    /** 今日被拒绝（DENY）的记录数。 */
    public long todayDeny;
    /** 今日去重活跃操作人数（排除匿名）。 */
    public long todayActiveUsers;

    public AuditStatsDto(long todayTotal, long todayDeny, long todayActiveUsers) {
        this.todayTotal = todayTotal;
        this.todayDeny = todayDeny;
        this.todayActiveUsers = todayActiveUsers;
    }
}
