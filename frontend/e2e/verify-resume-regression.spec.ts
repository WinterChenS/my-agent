import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 会话恢复回归（用户实测反馈的错乱）：
 *
 *   1) agent 正在回复中；
 *   2) 切到别的对话/页面（后台继续跑）；
 *   3) 切回当前会话 → 对话出现错乱。
 *
 * 两个根因（都在 ChatPage 的「接续正在跑的 run」里）：
 *   A. 接续时把"最后一条助手文本"直接覆盖成本轮的半截文本 —— 本轮还没落库时，
 *      那条助手文本其实是**上一轮的答案**，于是上一轮的答案被改写、位置也串了。
 *   B. 接续的轮询循环不会因为切走而停止 —— 切到 B 会话后，A 的循环还在往界面写
 *      （半截文本 + 收尾时 replayMessages(A 的消息)），把 A 的内容灌进正在看的 B。
 *
 * 桩用慢模型（slow-）：每片间隔 2.5s，这一轮能持续二十几秒，足够做"切走—切回"。
 */
test.describe("会话恢复：切走再切回不打乱对话", () => {
  test("另一会话在跑时切走再切回：上一轮答案保持完整、本轮追加新气泡、不串会话", async ({
    page,
    request,
  }) => {
    test.setTimeout(300_000);
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-slow-${stamp}`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);

      // 第一轮：等它**跑完**（这样 A 会话里有一条已落库的助手答案）
      await input.fill("第一轮");
      await input.press("Enter");
      await expect(page.getByTestId("user-text")).toHaveCount(1, { timeout: 30_000 });
      await expect(page.getByTestId("assistant-text")).toHaveCount(1, { timeout: 120_000 });
      /*
       * 必须等这一轮**跑完**再取第一条答案 —— 气泡刚出现时内容还在流式增长（实测取到过 "stu"）。
       * 用「停止按钮消失」作为结束信号：输入框是 disabled={false}（用户要求执行中也能打字），
       * 所以 contenteditable 一直是 true，拿它等待是空操作。
       */
      await expect(page.getByTestId("stop-button")).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId("stop-button")).toHaveCount(0, { timeout: 120_000 });
      const firstAnswer = await page.getByTestId("assistant-text").first().innerText();
      expect(firstAnswer.length).toBeGreaterThan(10);
      console.log(`[恢复] 第一轮答案已落库：${JSON.stringify(firstAnswer.slice(0, 20))}…`);

      // 会话 A 的 threadId（用于精确定位侧栏里的那一行）
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      const sessionA = (sessions.data as { id: number; agentId: number | null; threadId: string }[]).find(
        (s) => s.agentId === agentDbId,
      )!;

      // 第二轮：发出后**不等它跑完**，此时 A 正在后台跑
      await input.fill("第二轮");
      await input.press("Enter");
      await expect(page.getByTestId("stop-button")).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId("user-text")).toHaveCount(2);
      console.log("[恢复] 第二轮已在跑（未完成）");

      // 造一个**另一条会话 B**（空会话），切过去
      const created = await (
        await request.post(`${API}/api/sessions`, { headers, data: { agentId: agentDbId } })
      ).json();
      const threadB = created.data.threadId as string;
      await page.getByTestId("session-refresh").click();
      const rowB = page.locator(`[data-testid="session-item"][title="${threadB}"]`);
      await expect(rowB).toHaveCount(1, { timeout: 20_000 });
      await rowB.click();
      await expect(page).toHaveURL(new RegExp(`/chat/${created.data.id}$`));

      /*
       * ① 不串会话：B 是空会话，绝不该出现 A 正在生成的文本。
       *
       * 先"等切换落定"再断言：点 B 之后、B 的消息加载完成之前，界面显示的仍是 **A 的旧内容**
       * （URL 已经是 B）—— 那是切换的瞬时态，不是泄漏（实测诊断：url=/chat/B 而 items=0，
       * 说明被数到的那 1 条来自上一帧）。真正的泄漏会"持续"出现，所以这里要求**连续多次**都为 0。
       */
      await page.waitForTimeout(1500);
      for (let i = 0; i < 8; i += 1) {
        const leaked = await page.getByTestId("assistant-text").count();
        if (leaked > 0 && i === 0) {
          const detail = await page.evaluate(() => ({
            url: location.pathname,
            users: Array.from(document.querySelectorAll('[data-testid="user-text"]')).map((e) => (e.textContent ?? '').slice(0, 12)),
            assistants: Array.from(document.querySelectorAll('[data-testid="assistant-text"]')).map((e) => (e.textContent ?? '').slice(0, 30)),
            pending: document.querySelectorAll('[data-testid="pending-bubble"]').length,
          }));
          console.log('[恢复][诊断] B 视图内容 =', JSON.stringify(detail));
        }
        expect(leaked, "A 正在跑的文本不该写进 B 会话").toBe(0);
        await page.waitForTimeout(700);
      }
      console.log("[恢复] 切到 B 期间：B 未被 A 的在跑文本污染 ✓");

      // 切回 A
      const rowA = page.locator(`[data-testid="session-item"][title="${sessionA.threadId}"]`);
      await rowA.click();
      await expect(page).toHaveURL(new RegExp(`/chat/${sessionA.id}$`));

      // ② 上一轮的答案不能被改写：第一条助手气泡仍是第一轮的内容
      await expect(page.getByTestId("assistant-text").first()).toHaveText(firstAnswer, {
        timeout: 30_000,
      });
      // ③ 本轮是**追加**的新气泡（而不是把上一轮那条改掉）：助手气泡应有 2 条
      await expect(page.getByTestId("assistant-text")).toHaveCount(2, { timeout: 60_000 });
      // ④ 顺序正确：最后一条应是助手气泡（本轮的），而不是"半截文本跑到用户消息前面"
      const order = await page.evaluate(() =>
        Array.from(document.querySelectorAll('[data-testid="user-text"],[data-testid="assistant-text"]')).map(
          (el) => el.getAttribute("data-testid"),
        ),
      );
      console.log(`[恢复] 切回后气泡顺序 = ${JSON.stringify(order)}`);
      expect(order).toEqual(["user-text", "assistant-text", "user-text", "assistant-text"]);
      await page.screenshot({ path: "../docs/acceptance/task-42-resume-no-garble.png" });

      // 等第二轮跑完：两条答案都在、且都不为空
      await expect(page.getByTestId("assistant-text")).toHaveCount(2, { timeout: 180_000 });
      await expect(page.getByTestId("assistant-text").nth(1)).not.toHaveText("", { timeout: 180_000 });
      console.log("[恢复] 第二轮完成后两条答案都在 ✓");
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
  /**
   * 同一条链路的另一种入口：**刷新页面**（SSE 必然已断）。
   *
   * 这时内容只能来自"重放历史 + 接续正在跑的文本"，也就是修复 A 覆盖的那条路：
   * 重放出来的是 [user1, assistant1, user2]（本轮还没落库），接续的文本必须**追加**成新气泡；
   * 若盲目覆盖"最后一条助手文本"，就会把**上一轮的答案**改成本轮的半截文本（实测会复现）。
   */
  test("刷新页面后接续：追加本轮气泡，不覆盖上一轮答案", async ({ page, request }) => {
    test.setTimeout(300_000);
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-slow-${stamp}`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);
      await input.fill("第一轮");
      await input.press("Enter");
      await expect(page.getByTestId("stop-button")).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId("stop-button")).toHaveCount(0, { timeout: 120_000 });
      const firstAnswer = await page.getByTestId("assistant-text").first().innerText();
      expect(firstAnswer.length).toBeGreaterThan(10);
      console.log(`[刷新接续] 第一轮答案：${JSON.stringify(firstAnswer.slice(0, 20))}…`);

      // 第二轮发出后立刻**刷新页面**（SSE 断开，服务端继续跑）
      await input.fill("第二轮");
      await input.press("Enter");
      await expect(page.getByTestId("stop-button")).toBeVisible({ timeout: 30_000 });
      const urlBeforeReload = page.url();
      await page.reload();
      console.log(`[刷新接续] 已刷新：${urlBeforeReload.slice(-18)}`);

      /*
       * 刷新后：历史重放 + 接续在跑的那一轮。
       *
       * 关键：**必须在这一轮仍在跑的时候断言** —— 收尾时还会重拉一次落库消息，
       * 那次重拉会把错乱"修好"，等到那之后再断言就抓不到问题了（实测如此）。
       */
      await expect(page.getByTestId("user-text")).toHaveCount(2, { timeout: 60_000 });
      // 诊断：刷新那一刻服务端这一轮的状态（running / 已生成文本长度）
      const sessions2 = await (await request.get(`${API}/api/sessions`, { headers })).json();
      const sA = (sessions2.data as { id: number; agentId: number | null }[]).find((x) => x.agentId === agentDbId)!;
      const rs = await (await request.get(`${API}/api/sessions/${sA.id}/run`, { headers })).json();
      console.log(`[刷新接续][诊断] run 状态 = ${JSON.stringify({ running: rs.data?.running, textLen: (rs.data?.text ?? "").length, stopped: rs.data?.stopped })}`);
      await expect(page.getByTestId("assistant-text")).toHaveCount(1, { timeout: 60_000 });
      // 接续的文本必须**追加**成新气泡（修复前会改写第一条 = 把上一轮答案覆盖掉）
      await expect(page.getByTestId("assistant-text")).toHaveCount(2, { timeout: 60_000 });
      expect(
        await page.getByTestId("stop-button").count(),
        "断言时必须仍在这一轮运行中（否则收尾重拉已把界面修正，问题被掩盖）",
      ).toBe(1);
      // 上一轮的答案必须原样还在
      await expect(page.getByTestId("assistant-text").first()).toHaveText(firstAnswer);
      const order = await page.evaluate(() =>
        Array.from(document.querySelectorAll('[data-testid="user-text"],[data-testid="assistant-text"]')).map(
          (el) => el.getAttribute("data-testid"),
        ),
      );
      console.log(`[刷新接续] 运行中断言：气泡顺序 = ${JSON.stringify(order)}`);

      // 跑完后两条答案都完整
      await expect(page.getByTestId("stop-button")).toHaveCount(0, { timeout: 180_000 });
      await expect(page.getByTestId("assistant-text")).toHaveCount(2, { timeout: 180_000 });
      await expect(page.getByTestId("assistant-text").nth(1)).not.toHaveText("", { timeout: 60_000 });
      console.log("[刷新接续] 上一轮答案保持完整、本轮追加成功 ✓");
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
