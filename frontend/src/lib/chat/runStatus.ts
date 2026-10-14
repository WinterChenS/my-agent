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
 * 「上一轮」提示文案（tier1-upgrades ③）。
 *
 * 取舍（用户反馈后**两次收紧**，最终定成这个「不会骗人」的规则）：
 * **只在「已中断 / 出错」时提示** —— 这两种情况下用户确实看不到发生了什么，需要被告知原因。
 *
 * 「已完成」一律不提示：答案已经落在聊天记录里，再挂一句就是噪音（用户明确反馈过）。
 * 中途尝试过「已完成但答案不可见才提示」，但可见性要同时考虑接口拉到的消息、本地流式中的消息、
 * 新建会话、刷新重进等多条路径，实测反复误判 —— 不值得为了一句锦上添花的信息引入这种复杂度。
 */
export function lastRunHint(run: RunHistoryItem | null | undefined): string | null {
  if (!run || run.status === "RUNNING" || run.status === "FINISHED") {
    return null;
  }
  const label = runStatusLabel(run.status);
  const reason = (run.error ?? "").trim();
  return reason ? `上一轮：${label}（${reason}）` : `上一轮：${label}`;
}
