import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 会话历史搬到「菜单栏下半部分」后的两条新行为（用户要求）：
 *  · 在**任意菜单页面**都能看到历史、点开某条会话、刷新列表
 *  · 没有开始聊天时是欢迎页（ChatGPT 式）：输入框垂直居中 + 引导文案
 */
test.describe("会话历史侧栏与欢迎页", () => {
  test("历史列表常驻菜单栏：任意页面可见、可刷新、点开即进会话", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp);
    try {
      await loginAndSelectAgent(page, agentName);
      // 直接建一条会话（不必跑模型），拿到它的 threadId 以便精确定位列表行
      const created = await (
        await request.post(`${API}/api/sessions`, { headers, data: { agentId: agentDbId } })
      ).json();
      const threadId = created.data.threadId as string;
      console.log(`[侧栏] 新建会话 id=${created.data.id} threadId=${threadId}`);

      // 走到一个**管理页面**（不是聊天页）
      await page.getByRole("link", { name: "模型" }).click();
      await expect(page).toHaveURL(/\/admin\/models/);

      // ① 历史列表在菜单栏里 —— 管理页面同样可见
      const list = page.getByTestId("session-list");
      await expect(list).toBeVisible();
      console.log("[侧栏] 管理页面可见历史列表 = true");

      // ② 刷新后能看到刚建的会话
      const row = list.locator(`[data-testid="session-item"][title="${threadId}"]`);
      const before = await row.count();
      await page.getByTestId("session-refresh").click();
      await expect(row).toHaveCount(1, { timeout: 20_000 });
      console.log(`[侧栏] 刷新前命中=${before} 刷新后命中=${await row.count()}`);

      // ③ 在管理页面点这条历史 —— 应当跳到 /chat 并真的打开它
      await row.click();
      await expect(page).toHaveURL(/\/chat/, { timeout: 20_000 });
      await expect(
        page.locator(
          `[data-testid="session-item-row"][data-selected="true"] [data-testid="session-item"][title="${threadId}"]`,
        ),
      ).toHaveCount(1, { timeout: 20_000 });
      // 打开了会话 → 不再是欢迎页
      await expect(page.getByTestId("welcome")).toHaveCount(0);
      console.log("[侧栏] 从管理页面点历史 → 已进入 /chat 且该行选中 ✓");
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });

  test("欢迎页：没有开始聊天时输入框居中 + 有文案", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp);
    try {
      await page.setViewportSize({ width: 1280, height: 900 });
      await loginAndSelectAgent(page, agentName);
      // 再点一次「新建会话」，去掉首屏自动打开最近会话带来的竞态
      await page.getByTitle("新建会话").click();

      const welcome = page.getByTestId("welcome");
      await expect(welcome).toBeVisible({ timeout: 20_000 });
      await expect(welcome).toContainText("开始新的对话");
      console.log(`[欢迎页] 文案 = ${JSON.stringify((await welcome.innerText()).replace(/\s+/g, " "))}`);

      const box = (await page.getByTestId("composer").boundingBox())!;
      const center = box.y + box.height / 2;
      console.log(
        `[欢迎页] 输入框中心 y=${Math.round(center)} 视口中心 y=450 输入框顶部 y=${Math.round(box.y)}`,
      );
      // 居中（容差：文案在输入框上方，整体居中让输入框中心略低于视口中线）
      expect(Math.abs(center - 450)).toBeLessThan(140);
      // 不是贴在底部
      expect(box.y).toBeLessThan(900 - 200);
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
