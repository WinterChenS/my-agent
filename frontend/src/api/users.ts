import { apiFetch } from "./client";
import type { PageResult } from "./types";

/** 用户（响应里不含密码哈希）。 */
export interface UserRow {
  id: number;
  username: string;
  displayName: string | null;
  role: "ADMIN" | "USER";
  status: "ENABLED" | "DISABLED";
  createdAt?: string;
}

export interface UserPayload {
  username?: string;
  password?: string;
  displayName?: string | null;
  role?: "ADMIN" | "USER";
  status?: "ENABLED" | "DISABLED";
}

export interface UserListQuery {
  current?: number;
  size?: number;
  keyword?: string;
  role?: "ADMIN" | "USER" | "";
  status?: "ENABLED" | "DISABLED" | "";
}

/** 用户列表（仅 ADMIN）。 */
export function listUsers(query: UserListQuery = {}) {
  const params = new URLSearchParams();
  params.set("current", String(query.current ?? 1));
  params.set("size", String(query.size ?? 20));
  if (query.keyword?.trim()) params.set("keyword", query.keyword.trim());
  if (query.role) params.set("role", query.role);
  if (query.status) params.set("status", query.status);
  return apiFetch<PageResult<UserRow>>(`/api/users?${params.toString()}`);
}

export function createUser(payload: UserPayload) {
  return apiFetch<UserRow>("/api/users", { method: "POST", body: JSON.stringify(payload) });
}

export function updateUser(id: number, payload: UserPayload) {
  return apiFetch<UserRow>(`/api/users/${id}`, { method: "PUT", body: JSON.stringify(payload) });
}

export function resetUserPassword(id: number, password: string) {
  return apiFetch<void>(`/api/users/${id}/password`, {
    method: "PUT",
    body: JSON.stringify({ password }),
  });
}

export function deleteUser(id: number) {
  return apiFetch<void>(`/api/users/${id}`, { method: "DELETE" });
}
