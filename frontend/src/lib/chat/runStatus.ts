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
 * 取舍（用户反馈后收紧）：**只在它真的告诉你界面上看不到的信息时才显示**。
 *
 * - 进行中 → 不提示（页面本来就在实时显示）。
 * - 已完成 → 只有当那一轮的答案**没有出现在聊天记录里**时才提示
 *   （典型场景：后端跑完但我们从没看到；答案已在记录里就是纯噪音，用户明确反馈过）。
 * - 已中断 / 出错 → 一律提示，并带上原因（这是用户最需要知道的那类信息）。
 */
export function lastRunHint(
  run: RunHistoryItem | null | undefined,
  opts?: { answerVisible?: boolean },
): string | null {
  if (!run || run.status === "RUNNING") {
    return null;
  }
  if (run.status === "FINISHED") {
    // 答案已经摆在聊天记录里 → 不必再提示
    if (opts?.answerVisible) {
      return null;
    }
    const summary = (run.textSummary ?? "").trim();
    return summary ? `上一轮：已完成 · ${summary}` : "上一轮：已完成";
  }
  const label = runStatusLabel(run.status);
  const reason = (run.error ?? "").trim();
  return reason ? `上一轮：${label}（${reason}）` : `上一轮：${label}`;
}

/**
 * 判断「这一轮的答案是否已经出现在聊天记录里」。
 *
 * 聊天记录里存在**晚于**该轮开始时间的助手消息，说明那一轮的产出用户已经能看到了。
 */
export function isAnswerVisible(
  run: RunHistoryItem | null | undefined,
  assistantMessageTimes: string[],
): boolean {
  if (!run?.startedAt) {
    return false;
  }
  const started = Date.parse(run.startedAt);
  if (Number.isNaN(started)) {
    return false;
  }
  return assistantMessageTimes.some((time) => {
    const at = Date.parse(time);
    return !Number.isNaN(at) && at >= started;
  });
}
