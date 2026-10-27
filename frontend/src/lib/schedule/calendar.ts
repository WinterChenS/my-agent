/** 把 Day 键（YYYY-MM-DD）按本地时区拼出来，避免 new Date() 时区偏移。 */
export function dayKey(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 颜色深浅分档（0 = 无执行，1..4 递增）。 */
export function intensityOf(total: number): 0 | 1 | 2 | 3 | 4 {
  if (!total) return 0;
  if (total === 1) return 1;
  if (total <= 3) return 2;
  if (total <= 6) return 3;
  return 4;
}

export const INTENSITY_CLASS: Record<number, string> = {
  0: "bg-muted/40 text-muted-foreground",
  1: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900/40 dark:text-emerald-100",
  2: "bg-emerald-300 text-emerald-950 dark:bg-emerald-700/60 dark:text-emerald-50",
  3: "bg-emerald-500 text-white dark:bg-emerald-600 dark:text-white",
  4: "bg-emerald-700 text-white dark:bg-emerald-500 dark:text-white",
};

export function intensityClass(total: number): string {
  return INTENSITY_CLASS[intensityOf(total)];
}

