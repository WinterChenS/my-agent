import { apiFetch } from "./client";

/** 额度状态（tier1-upgrades ④）。 */
export type QuotaStatus = {
  usedToday: number | null;
  dailyQuota?: number | null;
  usedMonth: number | null;
  monthlyQuota?: number | null;
  currency: string;
  mode: "BLOCK" | "WARN";
  exceeded: boolean;
  exceededScope?: string | null;
  message?: string | null;
  nearLimit?: boolean;
};

/** 我（+指定 Agent）当前的额度与用量。 */
export function getMyQuota(agentId?: number | null) {
  const suffix = agentId ? `?agentId=${agentId}` : "";
  return apiFetch<QuotaStatus>(`/api/quota/me${suffix}`);
}

/** 管理员设置某用户的额度（dailyQuota/monthlyQuota 传 null 表示不限）。 */
export function setUserQuota(
  userId: number,
  body: {
    dailyQuota: number | null;
    monthlyQuota: number | null;
    currency?: string;
    mode?: "BLOCK" | "WARN";
  },
) {
  return apiFetch<QuotaStatus>(`/api/users/${userId}/quota`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

/** 管理员查看某用户的额度与用量（审计看板用）。 */
export function getUserQuota(userId: number) {
  return apiFetch<QuotaStatus>(`/api/users/${userId}/quota`);
}
