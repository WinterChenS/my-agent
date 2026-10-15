import { expect, test, type Page } from "@playwright/test";
import { createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 中文输入法（IME）场景的回归：**「导出excel」不能只剩「导出」**。
 *
 * 用户实测反馈的 bug：输入「导出excel」发送后，气泡里只有「导出」；发送键有时还是灰的。
 * 根因两条（都属于 contentEditable + IME 的经典坑）：
 *  ① 组字期间按下 Enter 是**输入法在上屏**，却被我们的 keydown 抢走直接发送 →
 *     没上屏的部分（excel）当然发不出去；
 *  ② 组字期间 onInput 被跳过，于是 parts 状态滞后 → 发送键按 parts 判断可用性，
 *     明明有字却是灰的。
 *
 * 这里用真实的合成事件序列复现（compositionstart → update → end），而不是靠 insertText，
 * 因为 insertText 不产生组字状态、根本触发不到这个 bug。
 */
async function composeInto(page: Page, text: string) {
  // 模拟输入法：开始组字 → 组字过程中文本进入 DOM（此时还没有 input 事件语义上的"提交"）
  const input = page.getByTestId("chat-input");
  await input.click();
  await page.evaluate((value) => {
    const el = document.querySelector('[data-testid="chat-input"]') as HTMLElement;
    el.dispatchEvent(new CompositionEvent("compositionstart", { bubbles: true }));
    // 组字期间浏览器会把候选文本放进 DOM，并派发 isComposing=true 的 input
    el.textContent = value;
    el.dispatchEvent(new InputEvent("input", { bubbles: true, isComposing: true }));
  }, text);
}

test.describe("中文输入法（IME）", () => {
  test.setTimeout(120_000);

  test("组字期间按 Enter 不会把未上屏的内容丢掉", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp);
    try {
      const input = await loginAndSelectAgent(page, agentName);

      // ① 先正常上屏「导出」
      await input.click();
      await page.keyboard.insertText("导出");

      // ② 再开始组字「excel」（此时屏幕上能看到，但状态里应还没有）
      await composeInto(page, "导出excel");

      // ③ 组字期间按 Enter：**必须什么都不发生**（Enter 归输入法，用来上屏/选词）
      await page.keyboard.press("Enter");
      await page.waitForTimeout(500);
      // 没有新气泡 → 说明没被误发
      await expect(page.getByTestId("user-text")).toHaveCount(0);
      console.log("[IME] 组字期间按 Enter：未误发送 ✓");

      // ④ 结束组字（上屏），此时应把内容同步进状态（发送键据此变可用）
      await page.evaluate(() => {
        const el = document.querySelector('[data-testid="chat-input"]') as HTMLElement;
        el.dispatchEvent(new CompositionEvent("compositionend", { bubbles: true }));
      });
      const sendButton = page.getByRole("button", { name: /发送|\\u27a4/ }).last();
      console.log("[IME] 上屏后发送键可用 =", !(await page.locator('button:has(svg)').last().isDisabled().catch(() => true)));

      // ⑤ 再按 Enter 应当真正发送，且文本完整（导出 + excel 都在）
      await page.keyboard.press("Enter");
      const bubble = page.getByTestId("user-text").first();
      await expect(bubble).toBeVisible({ timeout: 30_000 });
      const sent = (await bubble.innerText()).replace(/\\s+/g, " ").trim();
      console.log("[IME] 实际发出的内容:", JSON.stringify(sent));
      expect(sent).toContain("导出excel");
      expect(sent).not.toBe("导出");
      void sendButton;
    } finally {
      const sessions = await (await request.get("http://localhost:8080/api/sessions", { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) {
          await request.delete(`http://localhost:8080/api/sessions/${s.id}`, { headers });
        }
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
