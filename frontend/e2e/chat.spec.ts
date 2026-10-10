import { expect, test } from "@playwright/test";

/**
 * Task 4.3 浏览器级验收：
 *  1. 登录后可进入聊天页
 *  2. 发送消息 → 助手气泡**逐字**出现（采样到多个中间态）
 *  3. 刷新页面后历史消息仍在（历史回放）
 *  4. 后端不可用时显示错误态且页面不白屏（可重试）
 */
test("登录 → 发消息 → 流式气泡 → 刷新回放", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByText("登录 my-agent")).toBeVisible();
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();

  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });
  await expect(page.getByText("my-agent")).toBeVisible();

  // 等 Agent 下拉加载出验收用的 Agent（名称由脚本按运行生成，避免重跑冲突）
  const agentName = process.env.E2E_AGENT_NAME ?? "E2E-Chat-Agent";
  // 刷新恢复的是历史会话；新建会话才能重新选 Agent（会话与 Agent 绑定后不允许切换）
  await page.getByTitle("新建会话").click();
  const agentSelect = page.locator("select");
  await expect(agentSelect).toContainText(agentName, { timeout: 20_000 });
  await agentSelect.selectOption({ label: agentName });

  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("你好，请回复");
  await input.press("Enter");

  // 用户气泡立即出现
  await expect(page.getByText("你好，请回复")).toBeVisible();

  // 采样助手气泡：至少看到 2 个不同的中间态 => 说明是逐字流式而不是一次性出现
  const assistant = page.locator('[data-testid="assistant-text"]').last();
  const snapshots: string[] = [];
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const text = (await assistant.innerText().catch(() => "")) ?? "";
    if (text && text !== snapshots[snapshots.length - 1]) {
      snapshots.push(text);
    }
    if (text.includes("stub-ok")) {
      break;
    }
    await page.waitForTimeout(30);
  }

  expect(snapshots.length, `采样到的流式中间态: ${JSON.stringify(snapshots)}`).toBeGreaterThanOrEqual(2);
  expect(snapshots[snapshots.length - 1]).toContain("stub-ok");

  await page.screenshot({
    path: "../docs/acceptance/task-4.3-chat-streaming.png",
    fullPage: true,
  });

  // 刷新后历史仍在
  await page.reload();
  await expect(page.getByText("你好，请回复")).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-testid="assistant-text"]')).toContainText("stub-ok", {
    timeout: 15_000,
  });

  await page.screenshot({
    path: "../docs/acceptance/task-4.3-chat-history.png",
    fullPage: true,
  });
});

test("后端不可用时显示错误态且不白屏", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

  // 断掉所有 /api 与 /agui 请求，模拟后端不可用
  await page.route("**/api/**", (route) => route.abort());
  await page.route("**/agui/**", (route) => route.abort());

  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("这条应该失败");
  await input.press("Enter");
  // 页面不能白屏：侧边栏、输入框仍在，且出现错误提示与重试入口
  await expect(page.getByText("my-agent")).toBeVisible();
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible({ timeout: 20_000 });
  // 失败时不能把用户输入弄丢，否则“重试”无从重发
  await expect(input).toHaveValue("这条应该失败");

  await page.screenshot({
    path: "../docs/acceptance/task-4.3-chat-error-state.png",
    fullPage: true,
  });
});
