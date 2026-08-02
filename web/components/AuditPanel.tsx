"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError, getAuditStats, listAudit } from "@/lib/api";
import type { Permission } from "@/lib/permissions";
import type { AuditLogDto, AuditStats } from "@/lib/types";

const DECISION_LABELS: Record<string, string> = {
  ALLOW: "允许",
  DENY: "拒绝",
};

const PAGE_SIZES = [20, 50, 100];

function fmt(v: string | null): string {
  return v ? v.replace("T", " ").replace(/\.\d+$/, "") : "—";
}

export default function AuditPanel({
  token,
  can,
}: {
  token: string;
  can: (p: Permission) => boolean;
}) {
  const canRead = can("audit:read");
  const [items, setItems] = useState<AuditLogDto[]>([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [stats, setStats] = useState<AuditStats>({
    todayTotal: 0,
    todayDeny: 0,
    todayActiveUsers: 0,
  });
  const [onlyDeny, setOnlyDeny] = useState(false);
  const [onlyDelete, setOnlyDelete] = useState(false);
  const [traceInput, setTraceInput] = useState("");
  const [trace, setTrace] = useState("");
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  const load = useCallback(
    async (p: number, s: number) => {
      setLoading(true);
      try {
        const res = await listAudit(token, p, s, {
          decision: onlyDeny ? "DENY" : undefined,
          onlyDelete,
          traceId: trace || undefined,
        });
        setItems(res.content);
        setTotal(res.total);
        setTotalPages(res.totalPages);
        setPage(res.page);
        setErr("");
      } catch (e) {
        setErr(e instanceof ApiError ? `加载失败：${e.message}` : "加载失败");
      } finally {
        setLoading(false);
      }
    },
    [token, onlyDeny, onlyDelete, trace]
  );

  const loadStats = useCallback(async () => {
    try {
      setStats(await getAuditStats(token));
    } catch {
      /* 统计失败不影响列表 */
    }
  }, [token]);

  // size / 筛选 / 链路搜索变化回到第一页并刷新统计
  useEffect(() => {
    if (canRead) {
      load(0, size);
      loadStats();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, canRead, size, onlyDeny, onlyDelete, trace]);

  const clearTrace = () => {
    setTraceInput("");
    setTrace("");
  };

  if (!canRead) {
    return (
      <div className="card">
        <h2>审计日志 Audit</h2>
        <div className="noperm">无权限查看审计日志（需要 audit:read）。</div>
      </div>
    );
  }

  const goPrev = () => {
    if (page > 0) load(page - 1, size);
  };
  const goNext = () => {
    if (page < totalPages - 1) load(page + 1, size);
  };

  return (
    <div className="card">
      <h2>审计日志 Audit</h2>
      <p className="sub">
        网关在 PEP 裁决后统一发射审计事件，覆盖所有经网关的跨服务请求（登录后的读 / 写、
        审批、删除等）。本表仅管理员可见，按时间倒序分页展示。
      </p>
      {err && <div className="err">{err}</div>}

      <div className="stat-cards">
        <div className="stat-card">
          <div className="stat-num">{stats.todayTotal}</div>
          <div className="stat-label">今日操作</div>
        </div>
        <div className="stat-card stat-warn">
          <div className="stat-num">{stats.todayDeny}</div>
          <div className="stat-label">今日拒绝</div>
        </div>
        <div className="stat-card stat-ok">
          <div className="stat-num">{stats.todayActiveUsers}</div>
          <div className="stat-label">今日活跃用户</div>
        </div>
      </div>

      <div className="audit-toolbar">
        <div className="audit-filters">
          <label className="filter-chip">
            <input
              type="checkbox"
              style={{ width: "auto" }}
              checked={onlyDeny}
              onChange={(e) => setOnlyDeny(e.target.checked)}
            />
            只看拒绝
          </label>
          <label className="filter-chip">
            <input
              type="checkbox"
              style={{ width: "auto" }}
              checked={onlyDelete}
              onChange={(e) => setOnlyDelete(e.target.checked)}
            />
            只看删除
          </label>
        </div>
        <div className="audit-search">
          <input
            type="text"
            value={traceInput}
            placeholder="按链路 ID 查询…"
            onChange={(e) => setTraceInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") setTrace(traceInput.trim());
            }}
          />
          <button
            className="btn"
            disabled={loading}
            onClick={() => setTrace(traceInput.trim())}
          >
            搜索
          </button>
          {trace && (
            <button className="btn" onClick={clearTrace}>
              清空
            </button>
          )}
        </div>
        <label className="audit-size">
          每页
          <select
            value={size}
            onChange={(e) => setSize(Number(e.target.value))}
            disabled={loading}
          >
            {PAGE_SIZES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
          条
        </label>
      </div>

      {items.length === 0 && !loading ? (
        <div className="meta">暂无审计记录</div>
      ) : (
        <table className="cust-table audit-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>操作人</th>
              <th>动作</th>
              <th>资源</th>
              <th>结果</th>
              <th>链路</th>
              <th>路径</th>
            </tr>
          </thead>
          <tbody>
            {items.map((a) => (
              <tr key={a.id}>
                <td>{fmt(a.createdAt)}</td>
                <td>{a.actor}</td>
                <td>{a.action}</td>
                <td>{a.resourceId != null ? `#${a.resourceId}` : "—"}</td>
                <td>
                  <span
                    className={`status ${
                      a.decision === "ALLOW" ? "status-customer" : "status-deny"
                    }`}
                  >
                    {DECISION_LABELS[a.decision] ?? a.decision}
                  </span>
                </td>
                <td className="trace-id" title={`链路 ${a.traceId ?? ""}：用该 ID 在服务日志中 grep 串联整条调用链`}>
                  {a.traceId ?? "—"}
                </td>
                <td>{a.path}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="pager">
        <button className="btn" onClick={goPrev} disabled={page <= 0 || loading}>
          上一页
        </button>
        <span className="meta">
          {page + 1} / {totalPages}
        </span>
        <button
          className="btn"
          onClick={goNext}
          disabled={page >= totalPages - 1 || loading}
        >
          下一页
        </button>
      </div>
    </div>
  );
}
