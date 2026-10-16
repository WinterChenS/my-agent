import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 模型"想很久"时的占位 loading 气泡（用户要求）。
 *
 * 场景：推理型模型首字之前要静默好几秒，这期间一条 assistant 消息都还没产生，
 * 界面上只有用户气泡 → 看着像"没反应"。所以先渲染占位气泡，真内容到了它自动消失。
 *
 * 桩侧用 `STUB_THINK_MODEL`（模型名含 think-）在首字前静默 STUB_THINK_DELAY_MS（默认 5s）。
 */
test.describe("占位 loading 气泡", () => {
  test("首字之前显示占位气泡；真内容到达后占位消失", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-think-${stamp}`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);
      await input.fill("想清楚再回答");
      await input.press("Enter");

      // ① 模型还没出字时：占位气泡应当已经出现（这是本用例的核心）
      const pending = page.getByTestId("pending-bubble");
      await expect(page.getByTestId("user-text")).toHaveCount(1);
      await expect(pending).toBeVisible({ timeout: 8_000 });
      const textsAtPlaceholder = await page.getByTestId("assistant-text").count();
      console.log(`[占位] 占位气泡可见 ✓ 此时 assistant 气泡数 = ${textsAtPlaceholder}`);
      expect(textsAtPlaceholder, "占位阶段不应已有助手正文").toBe(0);
      await page.screenshot({ path: "../docs/acceptance/task-40-pending-bubble.png" });

      // ② 真内容到达后：助手气泡出现、占位消失
      await expect(page.getByTestId("assistant-text")).toHaveCount(1, { timeout: 60_000 });
      await expect(pending).toHaveCount(0, { timeout: 15_000 });
      console.log(`[占位] 真内容到达后占位已消失 ✓ 正文=${JSON.stringify((await page.getByTestId("assistant-text").innerText()).slice(0, 24))}`);

      // ③ 再发一轮：占位机制在后续轮次同样生效
      await expect(page.getByTestId("chat-input")).toHaveAttribute("contenteditable", "true", {
        timeout: 60_000,
      });
      await page.getByTestId("chat-input").click();
      await page.keyboard.type("再来一次");
      await page.keyboard.press("Enter");
      await expect(pending).toBeVisible({ timeout: 8_000 });
      await expect(page.getByTestId("assistant-text")).toHaveCount(2, { timeout: 60_000 });
      console.log("[占位] 第二轮同样先占位再出内容 ✓");
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
