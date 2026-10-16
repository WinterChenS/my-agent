import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 执行中的操作（用户要求）：
 *  · 排队：执行中发的消息先入队，这一轮跑完**自动发出去**
 *  · 撤回：入队后还没发出去，可以撤回，**内容回到输入框**
 * 按钮规则：不执行=发送；执行中空输入=停止；执行中有输入=停止+发送
 *
 * 注：原先还有「引导」（把补充信息立刻注入正在跑的这一轮）。实测查证后已摘掉：
 * 官方 steering 只存在于 2.1.0-BETA1 的 AgentSession，我们钉的 2.0.3 没有该 API，
 * 而 interrupt(msg) 的官方语义是"协作式停止"、其消息无任何消费方（详见 docs/backlog.md）。
 */
test.describe("执行中的排队 / 撤回 / 按钮规则", () => {
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
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
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
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });

  /**
   * 回归（用户实测反馈）：**已有会话里连发两条**，每条发送后输入框都必须清空。
   *
   * 为什么单独立一条：之前只有"新建会话"路径的用例 —— 那条路上 send() 里有个 await（建会话），
   * 把两次状态更新分开成两次渲染，清空侥幸生效；
   * 而在**已有会话**里两次更新会被 React **批处理**，靠 effect 回写就会漏掉 → 输入框残留文字。
   */
  test("已有会话里连发两条：每条发送后输入框都清空", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp);
    try {
      const input = await loginAndSelectAgent(page, agentName);
      // 第一条（这一步会建会话，走 await 路径）
      await input.click();
      await page.keyboard.type("第一条消息");
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("user-text")).toHaveCount(1, { timeout: 30_000 });
      await expect(page.getByTestId("chat-input")).toHaveAttribute("contenteditable", "true", {
        timeout: 60_000,
      });

      // 第二条：**会话已存在**，这里才是会出问题的路径
      await input.click();
      await page.keyboard.type("第二条消息");
      await page.keyboard.press("Enter");
      await expect(page.getByTestId("user-text")).toHaveCount(2, { timeout: 30_000 });
      const leftover = (await page.getByTestId("chat-input").innerText()).trim();
      console.log("[残留检查] 第二条发出后输入框内容 =", JSON.stringify(leftover));
      expect(leftover).toBe("");
    } finally {
      const sessions = await (await request.get("http://localhost:8080/api/sessions", { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
