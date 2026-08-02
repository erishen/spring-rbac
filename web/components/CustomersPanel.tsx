"use client";

import { useEffect, useState, type FormEvent } from "react";
import {
  ApiError,
  createCustomer,
  deleteCustomer,
  listCustomers,
  updateCustomer,
} from "@/lib/api";
import type { Permission } from "@/lib/permissions";
import type { CustomerDto, CustomerPage, UpdateCustomerRequest } from "@/lib/types";

const STATUS_OPTIONS = ["lead", "prospect", "customer"];

// 阶段中文标签（界面显示用；提交给后端的值仍是英文，保持 API 对齐）
const STATUS_LABELS: Record<string, string> = {
  lead: "线索",
  prospect: "商机",
  customer: "成交",
};

export default function CustomersPanel({
  token,
  can,
}: {
  token: string;
  can: (p: Permission) => boolean;
}) {
  const canRead = can("customers:read");
  const canCreate = can("customers:create");
  const canEdit = can("customers:update");
  const canDelete = can("customers:delete");
  const canMutate = canCreate || canEdit || canDelete; // 任一写权限即显示动作列/编辑态

  const [q, setQ] = useState("");
  const [customers, setCustomers] = useState<CustomerDto[]>([]);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  // 分页状态
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [appliedQ, setAppliedQ] = useState<string | undefined>(undefined);

  // 编辑态：null=不编辑；{id} 表示编辑某客户；"new" 表示新建
  const [editing, setEditing] = useState<number | "new" | null>(null);
  const [form, setForm] = useState({
    name: "",
    company: "",
    phone: "",
    email: "",
    status: "lead",
    notes: "",
  });
  const [busy, setBusy] = useState(false);

  async function refresh(pageNum = 0, sizeNum = size, query?: string) {
    const q2 = query ?? appliedQ;
    try {
      const p: CustomerPage = await listCustomers(token, q2, pageNum, sizeNum);
      setCustomers(p.content);
      setTotalElements(p.totalElements);
      setTotalPages(p.totalPages);
      setPage(p.number);
      setSize(p.size);
      setErr("");
    } catch (e) {
      setErr(e instanceof ApiError ? `加载失败：${e.message}` : "加载失败");
    }
  }

  useEffect(() => {
    if (canRead) refresh(0, size);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, canRead]);

  function startNew() {
    setForm({ name: "", company: "", phone: "", email: "", status: "lead", notes: "" });
    setEditing("new");
    setErr("");
    setMsg("");
  }

  function startEdit(c: CustomerDto) {
    setForm({
      name: c.name,
      company: c.company ?? "",
      phone: c.phone ?? "",
      email: c.email ?? "",
      status: c.status ?? "lead",
      notes: c.notes ?? "",
    });
    setEditing(c.id);
    setErr("");
    setMsg("");
  }

  async function search(e: FormEvent) {
    e.preventDefault();
    const qq = q.trim() || undefined;
    setAppliedQ(qq);
    await refresh(0, size, qq);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!form.name.trim()) {
      setErr("客户名称为必填");
      return;
    }
    setBusy(true);
    setErr("");
    setMsg("");
    try {
      if (editing === "new") {
        await createCustomer(token, {
          name: form.name.trim(),
          company: form.company.trim() || undefined,
          phone: form.phone.trim() || undefined,
          email: form.email.trim() || undefined,
          status: form.status,
          notes: form.notes.trim() || undefined,
        });
        setMsg("已新建客户");
      } else if (typeof editing === "number") {
        const body: UpdateCustomerRequest = {
          name: form.name.trim(),
          company: form.company.trim() || undefined,
          phone: form.phone.trim() || undefined,
          email: form.email.trim() || undefined,
          status: form.status,
          notes: form.notes.trim() || undefined,
        };
        await updateCustomer(token, editing, body);
        setMsg("已更新客户");
      }
      setEditing(null);
      await refresh(page, size, appliedQ);
    } catch (e) {
      setErr(e instanceof ApiError ? `保存失败：${e.message}` : "保存失败");
    } finally {
      setBusy(false);
    }
  }

  async function remove(c: CustomerDto) {
    if (!confirm(`确认提交「${c.name}」的删除申请？\n删除需管理员审批通过后才会生效。`)) return;
    setErr("");
    setMsg("");
    try {
      await deleteCustomer(token, c.id);
      setMsg(`已提交「${c.name}」的删除申请，待管理员审批通过后生效`);
      await refresh(page, size, appliedQ);
    } catch (e) {
      setErr(e instanceof ApiError ? `删除申请提交失败：${e.message}` : "删除申请提交失败");
    }
  }

  if (!canRead) {
    return (
      <div className="card">
        <h2>客户管理（CRM）</h2>
        <div className="noperm">无权限查看客户（需要 customers:read）。</div>
      </div>
    );
  }

  return (
    <div>
      <div className="card">
        <h2>客户管理（CRM）</h2>
        <p className="sub">
          客户数据由独立的 customer-service 提供；所有接口经网关 PEP 鉴权，按角色细分权限：
          <b>customers:read</b>（查看）/ <b>customers:create</b>（新建）/
          <b>customers:update</b>（编辑）/ <b>customers:delete</b>（申请删除）由后端 RBAC 实时裁决。
          删除客户会进入审批流，需管理员在「审批」页通过后才真正删除。
        </p>
        {err && <div className="err">{err}</div>}
        {msg && <div className="ok">{msg}</div>}

        <form onSubmit={search}>
          <div className="row">
            <div style={{ flex: 1 }}>
              <label>搜索（姓名/公司/电话/邮箱）</label>
              <input
                value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder="如 张伟 / 云启"
              />
            </div>
            <div style={{ display: "flex", alignItems: "flex-end" }}>
              <button className="btn" type="submit">
                搜索
              </button>
              {canCreate && (
                <button
                  className="btn btn-primary"
                  type="button"
                  style={{ marginLeft: 8 }}
                  onClick={startNew}
                >
                  + 新建客户
                </button>
              )}
            </div>
          </div>
        </form>

        <table className="cust-table">
          <thead>
            <tr>
              <th>姓名</th>
              <th>公司</th>
              <th>电话</th>
              <th>邮箱</th>
              <th>阶段</th>
              <th>备注</th>
              {canMutate && <th></th>}
            </tr>
          </thead>
          <tbody>
            {customers.map((c) => (
              <tr key={c.id}>
                <td className="name">{c.name}</td>
                <td>{c.company ?? "—"}</td>
                <td>{c.phone ?? "—"}</td>
                <td>{c.email ?? "—"}</td>
                <td>
                  <span className={`status status-${c.status ?? "lead"}`}>
                    {STATUS_LABELS[c.status ?? "lead"] ?? c.status ?? "线索"}
                  </span>
                </td>
                <td className="notes-cell" title={c.notes ?? undefined}>
                  <div className="notes-trunc">{c.notes ?? "—"}</div>
                </td>
                {(canEdit || canDelete) && (
                  <td className="actions">
                    {canEdit && (
                      <button className="btn" onClick={() => startEdit(c)}>
                        编辑
                      </button>
                    )}
                    {canDelete && (
                      <button
                        className="btn btn-danger"
                        style={{ marginLeft: 8 }}
                        onClick={() => remove(c)}
                      >
                        申请删除
                      </button>
                    )}
                  </td>
                )}
              </tr>
            ))}
            {!err && customers.length === 0 && (
              <tr>
                <td colSpan={canMutate ? 7 : 6} className="meta">
                  暂无客户
                </td>
              </tr>
            )}
          </tbody>
        </table>

        <div className="pager">
          <button
            className="btn"
            type="button"
            disabled={page <= 0}
            onClick={() => refresh(page - 1, size, appliedQ)}
          >
            上一页
          </button>
          <span className="pager-info">
            第 {page + 1} / {Math.max(totalPages, 1)} 页 · 共 {totalElements} 条
          </span>
          <button
            className="btn"
            type="button"
            disabled={page + 1 >= totalPages}
            onClick={() => refresh(page + 1, size, appliedQ)}
          >
            下一页
          </button>
          <select
            value={size}
            onChange={(e) => {
              const s = Number(e.target.value);
              setSize(s);
              refresh(0, s, appliedQ);
            }}
          >
            <option value={20}>20 / 页</option>
            <option value={50}>50 / 页</option>
            <option value={100}>100 / 页</option>
          </select>
        </div>

        {!canMutate && (
          <div className="noperm">
            {canRead
              ? "当前账号为只读（无 customers:create/update/delete），不能新建/编辑/删除客户。"
              : "当前账号无 customers:read，不能查看客户。"}
          </div>
        )}
      </div>

      {canMutate && editing !== null && (
        <div className="card">
          <h2>{editing === "new" ? "新建客户" : "编辑客户"}</h2>
          <form onSubmit={submit}>
            <div className="row">
              <div>
                <label>姓名 *</label>
                <input
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="必填"
                />
              </div>
              <div>
                <label>公司</label>
                <input
                  value={form.company}
                  onChange={(e) => setForm({ ...form, company: e.target.value })}
                />
              </div>
              <div>
                <label>电话</label>
                <input
                  value={form.phone}
                  onChange={(e) => setForm({ ...form, phone: e.target.value })}
                />
              </div>
              <div>
                <label>邮箱</label>
                <input
                  value={form.email}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                />
              </div>
              <div>
                <label>阶段</label>
                <select
                  value={form.status}
                  onChange={(e) => setForm({ ...form, status: e.target.value })}
                >
                  {STATUS_OPTIONS.map((s) => (
                    <option key={s} value={s}>
                      {STATUS_LABELS[s] ?? s}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div style={{ marginTop: 12 }}>
              <label>备注</label>
              <textarea
                value={form.notes}
                onChange={(e) => setForm({ ...form, notes: e.target.value })}
                rows={3}
                style={{ width: "100%" }}
              />
            </div>
            <div style={{ marginTop: 12, display: "flex", gap: 8 }}>
              <button className="btn btn-primary" type="submit" disabled={busy}>
                {busy ? "保存中…" : "保存"}
              </button>
              <button
                className="btn"
                type="button"
                onClick={() => setEditing(null)}
              >
                取消
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
