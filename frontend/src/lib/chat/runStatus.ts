import type { RunHistoryItem } from "@/api/sessions";

/** run 状态 → 中文文案（tier1-upgrades ③）。 */
export function runStatusLabel(status: RunHistoryItem["status"]): string {
  switch (status) {
    case "RUNNING":
      return "进行中";
    case "FINISHED":
      return "已完成";
    case "INTERRUPTED":
      return "已中断";
    case "ERROR":
      return "出错";
    default:
      return status;
  }
}

/**
 * 「上一轮」提示文案：让用户一眼看出上一次跑到哪（后端重启后尤为重要）。
 *
 * 已完成时带上摘要片段；中断/出错时说明原因。
 */
export function lastRunHint(run: RunHistoryItem | null | undefined): string | null {
  if (!run || run.status === "RUNNING") {
    return null;
  }
  const label = runStatusLabel(run.status);
  if (run.status === "FINISHED") {
    const summary = (run.textSummary ?? "").trim();
    return summary ? `上一轮：${label} · ${summary}` : `上一轮：${label}`;
  }
  const reason = (run.error ?? "").trim();
  return reason ? `上一轮：${label}（${reason}）` : `上一轮：${label}`;
}
