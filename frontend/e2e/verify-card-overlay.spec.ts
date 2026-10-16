import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

test.describe("交互卡片：悬浮在输入框上方", () => {
  test("卡片不渲染在聊天记录里，而是悬浮在输入框上方；选择后消失", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-card-${stamp}`,
    });
    try {
      await page.setViewportSize({ width: 1400, height: 900 });
      const input = await loginAndSelectAgent(page, agentName);
      await input.click();
      await page.keyboard.type("删掉订单历史分区");
      await page.keyboard.press("Enter");

      const overlay = page.getByTestId("card-overlay");
      await expect(overlay).toBeVisible({ timeout: 45_000 });

      // ① 卡片**只**出现在悬浮层里，聊天记录里没有（精确到 testid，避免误判：
      //    ToolCallCard 的折叠按钮无障碍名里也含 "继续"，用文本匹配会假阳性）
      const inOverlay = await page.locator('[data-testid="card-overlay"] [data-testid="confirm-card"]').count();
      const inTranscript = await page
        .locator('[data-testid="chat-column"] [data-testid="confirm-card"]')
        .count();
      console.log(`[卡片] 悬浮层内=${inOverlay} 聊天记录内=${inTranscript}`);
      expect(inOverlay).toBe(1);
      expect(inTranscript).toBe(0);

      // ② 悬浮层在输入框**上方**
      const ob = (await overlay.boundingBox())!;
      const cb = (await page.getByTestId("composer").boundingBox())!;
      console.log(`[卡片] 悬浮层底=${Math.round(ob.y + ob.height)} 输入区顶=${Math.round(cb.y)}`);
      expect(ob.y + ob.height).toBeLessThanOrEqual(cb.y + 2);

      await page.screenshot({ path: "../docs/acceptance/task-37-card-overlay.png" });

      // ④ 作出选择后悬浮层消失
      await overlay.getByRole("button", { name: "继续" }).click();
      await expect(overlay).toHaveCount(0, { timeout: 45_000 });
      console.log("[卡片] 选择后悬浮层已消失 ✓");
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
