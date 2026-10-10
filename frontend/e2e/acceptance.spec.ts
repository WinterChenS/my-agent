import { expect, test } from "@playwright/test";

/**
 * Task 9.1 全链路验收剧本（浏览器级）。
 *
 * 前置（由 scripts/verify-p9-acceptance.sh 准备）：
 *   - 模型桩在 8099，且会主动请求调用 echo 工具
 *   - 已建好两个 Agent：
 *       E2E_ALLOW_AGENT （ALWAYS_ALLOW + 绑定 MCP）    -> 普通工具调用卡
 *       E2E_APPROVAL_AGENT（REQUIRE_APPROVAL + 绑定 MCP）-> 审批卡
 *   - 环境变量携带两个 agentId
 *
 * 覆盖：登录 -> 普通对话与工具调用卡 -> 审批卡批准并继续 -> 审计看板有数。
 */

const ALLOW_AGENT = process.env.E2E_ALLOW_AGENT ?? "";
const APPROVAL_AGENT = process.env.E2E_APPROVAL_AGENT ?? "";

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });
}

test("剧本 1：普通工具调用卡（ALWAYS_ALLOW + MCP）", async ({ page }) => {
  await login(page);
  await page.getByTitle("新建会话").click();
  const agentSelect = page.locator("select");
  await expect(agentSelect).toContainText(ALLOW_AGENT, { timeout: 20_000 });
  await agentSelect.selectOption({ label: ALLOW_AGENT });

  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("请调用 echo 工具");
  await input.press("Enter");

  // 工具调用卡：折叠标题里应出现工具名 echo
  const toolCard = page.locator("button", { hasText: "echo" }).first();
  await expect(toolCard).toBeVisible({ timeout: 40_000 });
  await toolCard.click();
  // 展开后可见参数与结果
  await expect(page.getByText("参数").first()).toBeVisible();
  await expect(page.getByText("结果").first()).toBeVisible({ timeout: 40_000 });

  await page.screenshot({ path: "../docs/acceptance/task-9.1-tool-call-card.png", fullPage: true });
  await expect(page.getByText("生成中…")).toHaveCount(0, { timeout: 60_000 });
});

test("剧本 2：审批卡 -> 批准 -> 继续执行", async ({ page }) => {
  await login(page);
  await page.getByTitle("新建会话").click();
  const agentSelect = page.locator("select");
  await expect(agentSelect).toContainText(APPROVAL_AGENT, { timeout: 20_000 });
  await agentSelect.selectOption({ label: APPROVAL_AGENT });

  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("请调用 echo 工具");
  await input.press("Enter");

  // 审批卡出现（被权限系统拦截）
  await expect(page.getByText("需要审批")).toBeVisible({ timeout: 40_000 });
  await expect(page.getByText("调用参数")).toBeVisible();
  await page.screenshot({ path: "../docs/acceptance/task-9.1-approval-card.png", fullPage: true });

  // 批准 -> resume -> 卡片变灰、执行继续
  await page.getByRole("button", { name: "批准并继续" }).click();
  await expect(page.getByText("已批准")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "批准并继续" })).toHaveCount(0);

  await page.screenshot({ path: "../docs/acceptance/task-9.1-approval-approved.png", fullPage: true });
  await expect(page.getByText("生成中…")).toHaveCount(0, { timeout: 60_000 });
});

test("剧本 3：拒绝路径", async ({ page }) => {
  await login(page);
  await page.getByTitle("新建会话").click();
  const agentSelect = page.locator("select");
  await expect(agentSelect).toContainText(APPROVAL_AGENT, { timeout: 20_000 });
  await agentSelect.selectOption({ label: APPROVAL_AGENT });

  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("请调用 echo 工具");
  await input.press("Enter");

  await expect(page.getByText("需要审批")).toBeVisible({ timeout: 40_000 });
  await page.getByRole("button", { name: "拒绝" }).click();
  await expect(page.getByText("已拒绝")).toBeVisible({ timeout: 30_000 });

  await page.screenshot({ path: "../docs/acceptance/task-9.1-approval-denied.png", fullPage: true });
  await expect(page.getByText("生成中…")).toHaveCount(0, { timeout: 60_000 });
});
