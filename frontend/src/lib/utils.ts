import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** shadcn/ui 约定的类名合并工具。 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * max_tokens 是「单次生成的输出上限」，不是「上下文长度」——两者很容易被搞混
 * （实测有人填 1000000 想表达 1M 上下文，结果被厂商拒：valid range [1, 393216]）。
 *
 * 这里对可疑取值给出提醒；返回 null 表示没问题。
 */
export function maxTokensWarning(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) {
    return null; // 留空 = 用模型默认
  }
  const n = Number(trimmed);
  if (!Number.isFinite(n) || !Number.isInteger(n)) {
    return "请填整数（单位：tokens）。";
  }
  if (n <= 0) {
    return "必须为正整数。";
  }
  if (n >= 100_000) {
    return "这个值看起来像“上下文长度”。注意：max_tokens 是单次生成的输出上限（例如 DeepSeek 允许 [1, 393216]），不是能塞多长的对话。建议 8192 左右；留空则用模型默认。";
  }
  return null;
}
