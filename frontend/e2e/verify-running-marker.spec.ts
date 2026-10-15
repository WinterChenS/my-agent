import { expect, test } from "@playwright/test";
import { createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * C2：侧栏标记「正在跑」的会话。
 *
 * 背景：Phase 14 之后「断开不中断」是常态 —— 用户切走之后会话还在后台跑，
 * 以前中间看不出哪条在跑（只能等跑完收到站内提示）。
 */
test("正在跑的会话在侧栏有转圈标记", async ({ page, request }) => {
  test.setTimeout(120_000);
  const stamp = Date.now();
  // 模型名带 slow- ：桩会按模型名给慢速分片（约 2.5s/片），长任务才可观测。
  // 早先靠改全局 STUB_CHUNK_DELAY_MS，跑完忘恢复会让整轮 e2e 变慢并超时（实测踩过两次）。
  const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
    modelId: `stub-slow-${stamp}`,
  });
  try {
    const input = await loginAndSelectAgent(page, agentName);
    // 先建一个「别人的」会话，稍后切过去
    await input.click();
    await page.keyboard.type("第一条");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("chat-input")).toHaveAttribute("contenteditable", "true", { timeout: 60_000 });

    // 再发一条长的（桩每 2.5s 一片，会跑一会儿），立刻切到别的会话
    await input.click();
    await page.keyboard.type("第二条很长的话");
    await page.keyboard.press("Enter");
    await page.waitForTimeout(500);
    const items = page.locator("[data-testid='session-item']");
    await expect(items.first()).toBeVisible({ timeout: 20_000 });
    if ((await items.count()) > 1) {
      await items.nth(1).click();
    }

    // 侧栏应出现转圈标记
    const marker = page.getByTestId("session-running");
    await expect(marker.first()).toBeVisible({ timeout: 30_000 });
    console.log("[C2] 侧栏出现「正在跑」标记，数量 =", await marker.count());

    // 等这一轮跑完，标记应消失
    await expect(marker).toHaveCount(0, { timeout: 90_000 });
    console.log("[C2] 跑完后标记已消失");
  } finally {
    const sessions = await (await request.get("http://localhost:8080/api/sessions", { headers })).json();
    for (const s of sessions.data as { id: number; agentId: number | null }[]) {
      if (s.agentId === agentDbId) await request.delete(`http://localhost:8080/api/sessions/${s.id}`, { headers });
    }
    await deleteStubAgent(request, headers, agentDbId);
  }
});
