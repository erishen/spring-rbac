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
        }}
      >
        <input
          type="checkbox"
          checked={onlyPending}
          onChange={(e) => setOnlyPending(e.target.checked)}
        />
        仅看待审批
      </label>
      {err && <div className="err">{err}</div>}
      {msg && <div className="ok">{msg}</div>}

      {items.length === 0 ? (
        <div className="meta">暂无审批单</div>
      ) : (
        <table className="cust-table">
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
                <td>{a.type}</td>
                <td>{a.targetName ?? `#${a.targetId}`}</td>
                <td>{a.applicant}</td>
                <td>{a.createdAt ?? "—"}</td>
                <td>
                  <span
                    className={`status status-${
                      a.status === "PENDING"
                        ? "lead"
                        : a.status === "APPROVED"
                          ? "customer"
                          : "prospect"
                    }`}
                  >
                    {a.status}
                  </span>
                </td>
                <td className="actions">
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
                        style={{ marginLeft: 8 }}
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
    </div>
  );
}
