import { expect, test } from "@playwright/test";

/** tier1-upgrades ④：审计看板显示已用/额度，并可设置额度。 */
test("审计看板显示支出额度并可设置", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/audit");
  const card = page.getByTestId("quota-card");
  await expect(card).toBeVisible({ timeout: 20000 });

  // 选 admin 用户，额度卡片会载入
  await page.locator("#audit-user").selectOption({ index: 1 });
  await expect(page.getByTestId("quota-usage")).toContainText("今日已用", { timeout: 15000 });
  await expect(page.getByTestId("quota-usage")).toContainText("本月已用");
  console.log("额度显示:", (await page.getByTestId("quota-usage").innerText()).replace(/\s+/g, " "));

  // 设一个很小额度 → 保存 → 出现「已超出」提示
  await page.getByTestId("quota-daily").fill("0.000001");
  await page.getByTestId("quota-mode").selectOption("BLOCK");
  await page.getByTestId("quota-save").click();
  await expect(page.getByTestId("quota-notice")).toContainText("已保存", { timeout: 15000 });
  await expect(page.getByTestId("quota-exceeded")).toBeVisible({ timeout: 15000 });
  console.log("超限提示:", await page.getByTestId("quota-exceeded").innerText());

  // 清空额度（恢复不限）
  await page.getByTestId("quota-daily").fill("");
  await page.getByTestId("quota-save").click();
  await expect(page.getByTestId("quota-usage")).toContainText("/ 日额度 不限", { timeout: 15000 });
});
