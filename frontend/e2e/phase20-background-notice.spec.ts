import { expect, test } from "@playwright/test";
import {
  createStubAgent,
  deleteNewSessions,
  deleteStubAgent,
  loginAndSelectAgent,
  snapshotSessionIds,
} from "./helpers/api";

/** 本次用例新建的会话（收尾时删掉，别把侧边栏塞满真实数据）。 */
let sessionsBefore: Set<number> = new Set();
test.beforeEach(async ({ request }) => {
  sessionsBefore = await snapshotSessionIds(request);
});
test.afterEach(async ({ request }) => {
  await deleteNewSessions(request, sessionsBefore);
});

/**
 * tier1-upgrades ③ 的「长任务完成通知」：
 * 在当前会话发起一轮 → 立刻切到别的会话 → 原会话跑完后应出现站内提示且能跳回。
 */
test("后台会话跑完会给站内提示", async ({ page, request }) => {
  // 自给自足：造一个指向本地桩的 Agent（不依赖你维护的模型/网关是否可用）
  const stamp = Date.now();
  // 模型名带 slow- ：桩会按模型名给慢速分片（约 2.5s/片），长任务才可观测。
  // 早先靠改全局 STUB_CHUNK_DELAY_MS，跑完忘恢复会让整轮 e2e 变慢并超时（实测踩过两次）。
  const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
    modelId: `stub-slow-${stamp}`,
  });
  try {
  const input = await loginAndSelectAgent(page, agentName);
  await input.fill("后台跑一轮");
  await input.press("Enter");

  // 立刻切到另一个已有会话（让原会话的 run 在后台跑）
  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });
  const items = page.locator("[data-testid='session-item']");
  const count = await items.count();
  for (let i = 0; i < count; i += 1) {
    const text = await items.nth(i).innerText();
    if (!text.includes("后台跑一轮")) {
      await items.nth(i).click();
      break;
    }
  }

  // 等站内提示出现（桩每块 2.5s，最多等 60s）
  const notice = page.getByTestId("run-notice");
  await expect(notice).toBeVisible({ timeout: 60000 });
  console.log("站内提示:", await notice.innerText());
  await expect(page.getByTestId("run-notice-jump")).toBeVisible();
  } finally {
    // 收尾：删掉自己造的 Agent/模型/供应商（会话由上面的 afterEach 按快照删除）
    await deleteStubAgent(request, headers, agentDbId);
  }
});
