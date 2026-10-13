import { expect, test } from "@playwright/test";

/**
 * tier1-upgrades ③ 的「长任务完成通知」：
 * 在当前会话发起一轮 → 立刻切到别的会话 → 原会话跑完后应出现站内提示且能跳回。
 */
test("后台会话跑完会给站内提示", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  // 新建一个会话（直接输入即新建）
  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("后台跑一轮");
  await input.press("Enter");

  // 立刻切到另一个已有会话（让原会话的 run 在后台跑）
  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });
  const items = page.locator("[data-testid='session-item']");
  const count = await items.count();
  for (let i = 0; i < count; i += 1) {
    const text = await items.nth(i).innerText();
    if (!text.includes("后台跑一轮")) {
      await items.nth(i).click();
      break;
    }
  }

  // 等站内提示出现（桩每块 2.5s，最多等 60s）
  const notice = page.getByTestId("run-notice");
  await expect(notice).toBeVisible({ timeout: 60000 });
  console.log("站内提示:", await notice.innerText());
  await expect(page.getByTestId("run-notice-jump")).toBeVisible();
});
