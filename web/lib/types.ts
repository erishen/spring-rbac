export interface TokenResponse {
  token: string;
}

export interface UserInfo {
  username: string;
}

export interface RoleDto {
  id: number;
  name: string;
  parentId: number | null;
}

export interface PermissionDto {
  id: number;
  name: string;
}

export interface UserRoleView {
  roleId: number;
  roleName: string;
}

export interface CheckResponse {
  allowed: boolean;
}

export interface CustomerDto {
  id: number;
  name: string;
  company: string | null;
  phone: string | null;
  email: string | null;
  status: string | null;
  notes: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 后端分页返回封装（Spring Data Page 序列化）。 */
export interface CustomerPage {
  content: CustomerDto[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  numberOfElements: number;
  empty: boolean;
}

export interface CreateCustomerRequest {
  name: string;
  company?: string;
  phone?: string;
  email?: string;
  status?: string;
  notes?: string;
}

export interface UpdateCustomerRequest {
  name?: string;
  company?: string;
  phone?: string;
  email?: string;
  status?: string;
  notes?: string;
}

/** 审批请求（删除客户等需审批的操作）。 */
export interface ApprovalDto {
  id: number;
  type: string;
  targetId: number;
  targetName: string | null;
  applicant: string;
  status: string;
  createdAt: string | null;
  decidedAt: string | null;
  approver: string | null;
  decisionNote: string | null;
}

export interface DeleteResult {
  deleted: boolean;
  approvalId: number | null;
}
