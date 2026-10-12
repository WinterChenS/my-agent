import { expect, test } from "@playwright/test";

/** tier1-upgrades ②：上下文可见可控（用量条 + 压缩 + 清空）。 */
test("会话里能看到上下文用量，并能压缩/清空", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  // 等会话列表出来（用户已有会话），点第一个
  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });
  await page.locator("[data-testid='session-item']").first().click();

  // 用量条出现并显示 tokens 与阈值
  const bar = page.getByTestId("context-bar");
  await expect(bar).toBeVisible({ timeout: 15000 });
  await expect(page.getByTestId("context-usage")).toContainText("tokens");
  await expect(page.getByTestId("context-usage")).toContainText("触发阈值");

  // 压缩上下文 → 有反馈
  await page.getByTestId("context-compact").click();
  await expect(page.getByTestId("context-notice")).toBeVisible({ timeout: 60000 });
  console.log("压缩反馈:", await page.getByTestId("context-notice").innerText());

  // 清空上下文 → 有反馈且用量归零
  await page.getByTestId("context-clear").click();
  await expect(page.getByTestId("context-notice")).toContainText("已清空上下文", { timeout: 30000 });
  await expect(page.getByTestId("context-usage")).toContainText("上下文 0");
});
