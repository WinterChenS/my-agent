import { expect, test } from "@playwright/test";

/**
 * Task 8.2 浏览器级验收：审计看板展示 Task 8.1 造的数据，且筛选生效。
 *
 * 依赖前置：后端已跑过若干次对话（token_usage 有数据）。
 */
test("审计看板展示 Token 数据并可筛选", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

  await page.goto("/admin/audit");
  await expect(page.getByText("Token 审计")).toBeVisible({ timeout: 15_000 });

  // 合计 Tokens 卡片应有非零数值
  const totalCard = page.locator("text=合计 Tokens").locator("xpath=../..");
  await expect(totalCard).toBeVisible();
  await expect(totalCard).not.toContainText("合计 Tokens0");

  // 三个趋势/分布区块存在
  await expect(page.getByText("按日趋势")).toBeVisible();
  await expect(page.getByText("按模型分布")).toBeVisible();
  await expect(page.getByText("用量明细")).toBeVisible();

  // 趋势 SVG 应至少有一根柱子
  await expect(page.locator('svg[aria-label="按日 Token 趋势"] rect').first()).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-8.2-audit-dashboard.png", fullPage: true });

  // 筛选：把起始日期设到未来，数据应为 0（证明筛选真的生效）
  await page.getByLabel("起始日期").fill("2099-01-01");
  await page.getByRole("button", { name: "查询" }).click();
  await expect(page.getByText("暂无数据").first()).toBeVisible({ timeout: 15_000 });

  await page.screenshot({ path: "../docs/acceptance/task-8.2-audit-filtered.png", fullPage: true });
});
