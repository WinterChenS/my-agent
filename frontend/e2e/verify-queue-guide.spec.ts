import { expect, test } from "@playwright/test";
import { createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 执行中的三种操作（用户要求）：
 *  · 排队：执行中发的消息先入队，这一轮跑完**自动发出去**
 *  · 撤回：入队后还没发出去，可以撤回，**内容回到输入框**
 *  · 引导：把补充信息**立刻**注入正在跑的这一轮（官方 interrupt(Msg)）
 * 按钮规则：不执行=发送；执行中空输入=停止；执行中有输入=停止+发送
 */
test.describe("执行中的排队 / 撤回 / 引导", () => {
  test.setTimeout(180_000);

  test("排队：执行中发的消息在跑完后自动发出；可撤回且内容回到输入框", async ({ page, request }) => {
    const stamp = Date.now();
    // 慢速模型：让第一轮跑得久一点，观察得到"执行中"
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-slow-${stamp}`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);

      // 第一轮（会跑一会儿）
      await input.click();
      await page.keyboard.type("第一轮");
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("stop-button")).toBeVisible({ timeout: 30_000 });
      console.log("[排队] 已进入执行中");

      // 执行中输入并发送 → 入队
      await input.click();
      await page.keyboard.type("排队的一条");
      await page.keyboard.press("Enter");
      const queued = page.getByTestId("queued-item");
      await expect(queued).toHaveCount(1, { timeout: 20_000 });
      console.log("[排队] 已入队:", (await queued.first().innerText()).replace(/\s+/g, " "));

      // ① 撤回：内容回到输入框
      await page.getByTestId("queued-withdraw").click();
      await expect(queued).toHaveCount(0, { timeout: 10_000 });
      await expect(input).toHaveText(/排队的一条/, { timeout: 10_000 });
      console.log("[撤回] 内容已回到输入框:", (await input.innerText()).trim());

      // ② 再发一次。注意：此时第一轮**可能已经跑完**（撤回转了几百毫秒），
      //    那样 Enter 就直接发送、不入队 —— 两种结果都对，所以不断言"一定入队"，
      //    只断言"这条最终会作为用户消息出现"。
      await input.click();
      await page.keyboard.press("End");
      await page.keyboard.press("Enter");
      const queuedNow = await queued.count();
      console.log("[排队] 再次提交：已入队 =", queuedNow === 1, "（为 false 说明第一轮已结束、直接发出）");

      // 自动发出 / 直接发出：气泡里应出现这条（用户消息数 >= 2）
      await expect(page.getByTestId("user-text")).toHaveCount(2, { timeout: 120_000 });
      const texts = await page.getByTestId("user-text").allInnerTexts();
      console.log("[排队] 跑完后自动发出的内容:", JSON.stringify(texts.map((t) => t.replace(/\s+/g, " ").trim())));
      expect(texts.some((t) => t.includes("排队的一条"))).toBe(true);
      await expect(queued).toHaveCount(0);
    } finally {
      const sessions = await (await request.get("http://localhost:8080/api/sessions", { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`http://localhost:8080/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });

  test("按钮规则：不执行=发送；执行中空=停止；执行中有内容=停止+发送", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-slow-${stamp}`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);

      // 不执行 + 有内容 → 只有发送
      await input.click();
      await page.keyboard.type("一条");
      await expect(page.getByTestId("send-button")).toHaveCount(1);
      await expect(page.getByTestId("stop-button")).toHaveCount(0);
      console.log("[按钮] 不执行：发送=1 停止=0 ✓");

      await page.keyboard.press("Enter");
      await expect(page.getByTestId("stop-button")).toBeVisible({ timeout: 30_000 });
      // 执行中 + 空输入 → 只有停止
      await expect(page.getByTestId("send-button")).toHaveCount(0);
      console.log("[按钮] 执行中(空)：发送=0 停止=1 ✓");

      // 执行中 + 有内容 → 停止 + 发送
      await input.click();
      await page.keyboard.type("排队");
      await expect(page.getByTestId("send-button")).toHaveCount(1, { timeout: 10_000 });
      await expect(page.getByTestId("stop-button")).toHaveCount(1);
      console.log("[按钮] 执行中(有内容)：发送=1 停止=1 ✓");
    } finally {
      const sessions = await (await request.get("http://localhost:8080/api/sessions", { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`http://localhost:8080/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
