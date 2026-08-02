"use client";

import { useEffect, useState } from "react";
import {
  ApiError,
  approveApproval,
  listApprovals,
  rejectApproval,
} from "@/lib/api";
import type { Permission } from "@/lib/permissions";
import type { ApprovalDto } from "@/lib/types";

const TYPE_LABELS: Record<string, string> = {
  DELETE_CUSTOMER: "删除客户",
};

const STATUS_LABELS: Record<string, string> = {
  PENDING: "待审批",
  APPROVED: "已通过",
  REJECTED: "已驳回",
};

/** 把后端 ISO 时间（如 2026-08-02T08:12:33.123）整理为可读形式。 */
function fmtTime(v: string | null): string {
  if (!v) return "—";
  return v.replace("T", " ").replace(/\.\d+$/, "");
}

function StatusBadge({ status }: { status: string }) {
  const cls =
    status === "PENDING"
      ? "lead"
      : status === "APPROVED"
        ? "customer"
        : "prospect";
  return (
    <span className={`status status-${cls}`}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

export default function ApprovalsPanel({
  token,
  can,
}: {
  token: string;
  can: (p: Permission) => boolean;
}) {
  const canApprove = can("customers:approve");
  const [items, setItems] = useState<ApprovalDto[]>([]);
  const [onlyPending, setOnlyPending] = useState(true);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ApprovalDto | null>(null);

  async function refresh() {
    try {
      const list = await listApprovals(token, onlyPending ? "PENDING" : undefined);
      setItems(list);
      setErr("");
    } catch (e) {
      setErr(e instanceof ApiError ? `加载失败：${e.message}` : "加载失败");
    }
  }

  useEffect(() => {
    if (canApprove) refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, canApprove, onlyPending]);

  async function decide(id: number, approve: boolean) {
    let note: string | undefined;
    if (!approve) {
      const input = window.prompt("驳回理由（可选）：");
      if (input === null) return; // 用户取消
      note = input.trim() || undefined;
    }
    setBusyId(id);
    setErr("");
    setMsg("");
    try {
      if (approve) {
        await approveApproval(token, id);
        setMsg("已通过，客户已删除（如仍存在）");
      } else {
        await rejectApproval(token, id, note);
        setMsg("已驳回该删除申请");
      }
      await refresh();
    } catch (e) {
      setErr(e instanceof ApiError ? `操作失败：${e.message}` : "操作失败");
    } finally {
      setBusyId(null);
    }
  }

  if (!canApprove) {
    return (
      <div className="card">
        <h2>审批（Approvals）</h2>
        <div className="noperm">无审批权限（需要 customers:approve）。</div>
      </div>
    );
  }

  return (
    <div className="card">
      <h2>审批（Approvals）</h2>
      <p className="sub">
        删除客户会进入此处待审批队列。仅拥有 <b>customers:approve</b> 的管理员可批准 / 驳回；
        批准后才会真实删除客户数据。
      </p>
      <label
        style={{
          display: "inline-flex",
          gap: 6,
          alignItems: "center",
          marginBottom: 12,
          whiteSpace: "nowrap",
        }}
      >
        <input
          type="checkbox"
          checked={onlyPending}
          onChange={(e) => setOnlyPending(e.target.checked)}
          style={{ width: "auto" }}
        />
        仅看待审批
      </label>
      {err && <div className="err">{err}</div>}
      {msg && <div className="ok">{msg}</div>}

      {items.length === 0 ? (
        <div className="meta">暂无审批单</div>
      ) : (
        <table className="cust-table approvals-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>类型</th>
              <th>客户</th>
              <th>申请人</th>
              <th>申请时间</th>
              <th>状态</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {items.map((a) => (
              <tr key={a.id}>
                <td>{a.id}</td>
                <td>{TYPE_LABELS[a.type] ?? a.type}</td>
                <td>
                  {a.targetName ?? "—"}
                  <span style={{ color: "var(--muted)", fontSize: 12 }}>
                    {" "}
                    #{a.targetId}
                  </span>
                </td>
                <td>{a.applicant}</td>
                <td>{fmtTime(a.createdAt)}</td>
                <td>
                  <StatusBadge status={a.status} />
                </td>
                <td className="actions">
                  <button
                    className="btn"
                    style={{ marginRight: 6 }}
                    onClick={() => setDetail(a)}
                  >
                    详情
                  </button>
                  {a.status === "PENDING" && (
                    <>
                      <button
                        className="btn btn-primary"
                        disabled={busyId === a.id}
                        onClick={() => decide(a.id, true)}
                      >
                        通过
                      </button>
                      <button
                        className="btn btn-danger"
                        style={{ marginLeft: 6 }}
                        disabled={busyId === a.id}
                        onClick={() => decide(a.id, false)}
                      >
                        驳回
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {detail && (
        <div className="modal-backdrop" onClick={() => setDetail(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <h3>审批详情 #{detail.id}</h3>
              <button
                className="btn"
                type="button"
                onClick={() => setDetail(null)}
              >
                关闭
              </button>
            </div>
            <div className="modal-body">
              <div className="detail-row">
                <div className="k">类型</div>
                <div className="v">{TYPE_LABELS[detail.type] ?? detail.type}</div>
              </div>
              <div className="detail-row">
                <div className="k">客户</div>
                <div className="v">
                  {detail.targetName ?? "—"} (#{detail.targetId})
                </div>
              </div>
              <div className="detail-row">
                <div className="k">申请人</div>
                <div className="v">{detail.applicant}</div>
              </div>
              <div className="detail-row">
                <div className="k">申请时间</div>
                <div className="v">{fmtTime(detail.createdAt)}</div>
              </div>
              <div className="detail-row">
                <div className="k">状态</div>
                <div className="v">
                  <StatusBadge status={detail.status} />
                </div>
              </div>
              <div className="detail-row">
                <div className="k">审批时间</div>
                <div className="v">{fmtTime(detail.decidedAt)}</div>
              </div>
              <div className="detail-row">
                <div className="k">审批人</div>
                <div className="v">{detail.approver ?? "—"}</div>
              </div>
              <div className="detail-row">
                <div className="k">审批意见</div>
                <div className="v">{detail.decisionNote ?? "—"}</div>
              </div>
            </div>
            {detail.status === "PENDING" && (
              <div className="modal-foot">
                <button
                  className="btn btn-primary"
                  disabled={busyId === detail.id}
                  onClick={() => decide(detail.id, true)}
                >
                  通过
                </button>
                <button
                  className="btn btn-danger"
                  disabled={busyId === detail.id}
                  onClick={() => decide(detail.id, false)}
                >
                  驳回
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
