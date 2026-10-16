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
  /**
   * 回归（用户实测指出）：**本轮已经显示了东西**（这里是工具调用行）时，不该再挂占位气泡。
   *
   * 原先的判据只看"有没有助手正文"，于是工具行已经列出来了、占位气泡还跟在下面。
   * 现在改为"本轮（最后一条用户消息之后）有任何 item 就不占位"。
   *
   * 桩侧用 `stub-script-slow-`（同时命中 script- 与 slow-）：先 write_file（工具行随即显示），
   * 之后每片内容之间还有 2.5s 延迟，于是"只有工具行、还没正文"的窗口足够宽，便于观察。
   */
  test("本轮已显示工具调用行时，不显示占位气泡", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-script-slow-${stamp}`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);
      await input.fill("做个报告");
      await input.press("Enter");

      // 等工具调用行出现（此时还没有助手正文）
      const toolRow = page.getByTestId("tool-call").first();
      await expect(toolRow).toBeVisible({ timeout: 30_000 });
      const texts = await page.getByTestId("assistant-text").count();
      const pending = await page.getByTestId("pending-bubble").count();
      console.log(
        `[占位] 工具行已显示：tool-call=1 assistant-text=${texts} pending-bubble=${pending}`,
      );
      expect(pending, "已经有工具调用行时不该再显示占位气泡").toBe(0);
      await page.screenshot({ path: "../docs/acceptance/task-41-no-pending-with-tool.png" });

      // 继续采样：工具行在场期间，占位气泡始终不应出现
      for (let i = 0; i < 12; i += 1) {
        const toolRows = await page.getByTestId("tool-call").count();
        const shown = await page.getByTestId("pending-bubble").count();
        if (toolRows > 0) {
          expect(shown, "工具行在场时不该出现占位气泡").toBe(0);
        }
        await page.waitForTimeout(300);
      }
      console.log("[占位] 工具行在场期间占位气泡始终未出现 ✓");

      // 最终仍有正常回复
      await expect(page.getByTestId("assistant-text")).toHaveCount(1, { timeout: 90_000 });
      console.log("[占位] 本轮正常出结果 ✓");
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
}
);
