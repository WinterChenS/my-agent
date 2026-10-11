/**
 * 会话列表的时间分组（Phase 13 优化）。
 *
 * <p>用户反馈：会话一多，左侧就是一长条散列，找不到「刚才那个」。
 * 这里按「今天 / 昨天 / 最近 7 天 / 最近 30 天 / 更早」分组，纯函数便于单测。
 */

export interface SessionLike {
  id: number;
  title?: string | null;
  lastActiveAt?: string | null;
  createdAt?: string;
}

export interface SessionGroup<T> {
  /** 分组标题 */
  label: string;
  /** 组内会话（保持传入顺序） */
  items: T[];
}

export const GROUP_LABELS = ["今天", "昨天", "最近 7 天", "最近 30 天", "更早"] as const;

/** 取用于分组的时间（优先 lastActiveAt，其次 createdAt）；解析不出来返回 null。 */
export function sessionTime(session: SessionLike): Date | null {
  const raw = session.lastActiveAt ?? session.createdAt ?? null;
  if (!raw) {
    return null;
  }
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** 相对「当天 00:00」的天数差：0=今天，1=昨天，…；无法判断时返回 Number.MAX_SAFE_INTEGER。 */
export function daysAgo(session: SessionLike, now: Date): number {
  const time = sessionTime(session);
  if (!time) {
    return Number.MAX_SAFE_INTEGER;
  }
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startOfThatDay = new Date(
    time.getFullYear(),
    time.getMonth(),
    time.getDate(),
  ).getTime();
  const diff = Math.floor((startOfToday - startOfThatDay) / 86_400_000);
  // 时钟漂移导致的「未来时间」并入今天
  return diff < 0 ? 0 : diff;
}

export function groupOf(session: SessionLike, now: Date): string {
  const diff = daysAgo(session, now);
  if (diff === 0) {
    return "今天";
  }
  if (diff === 1) {
    return "昨天";
  }
  if (diff <= 7) {
    return "最近 7 天";
  }
  if (diff <= 30) {
    return "最近 30 天";
  }
  return "更早";
}

/** 按时间分组（只返回非空分组，顺序固定）。 */
export function groupSessionsByTime<T extends SessionLike>(
  sessions: T[],
  now: Date = new Date(),
): SessionGroup<T>[] {
  const buckets = new Map<string, T[]>();
  for (const label of GROUP_LABELS) {
    buckets.set(label, []);
  }
  for (const session of sessions) {
    buckets.get(groupOf(session, now))!.push(session);
  }
  return GROUP_LABELS.filter((label) => buckets.get(label)!.length > 0).map((label) => ({
    label,
    items: buckets.get(label)!,
  }));
}
