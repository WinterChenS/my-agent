import { expect, test } from "@playwright/test";

/**
 * 用户反馈复现（两件事一起验）：
 *  1) 跑完一轮、答案就在聊天记录里 → 不该再挂「上一轮：已完成」噪音；
 *  2) 之后点「新建会话」→ 不该把上一个会话的提示带到空白新会话。
 */
test("已完成且答案可见时不提示；新建会话不残留", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  // 新建会话 → 发一轮（桩会正常回答）
  await page.getByTitle("新建会话").click();
  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("验证提示噪音");
  await input.press("Enter");
  await expect(page.getByText("stub-ok", { exact: false }).first()).toBeVisible({ timeout: 60000 });
  console.log("一轮跑完，答案已出现在聊天里");
  await page.waitForTimeout(2000);

  // 已完成的答案可见 → 输入框上方不该有「上一轮」提示
  const hint = await page.getByTestId("last-run-hint").count();
  console.log("同一会话内 上一轮提示 =", hint, hint > 0 ? await page.getByTestId("last-run-hint").innerText() : "");
  expect(hint).toBe(0);

  // 重新打开该会话（模拟刷新后重进）→ 仍不该有提示
  await page.reload();
  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });
  await page.locator("[data-testid='session-item']").first().click();
  await page.waitForTimeout(2000);
  const hintAfterReload = await page.getByTestId("last-run-hint").count();
  console.log("重开会话后 上一轮提示 =", hintAfterReload,
    hintAfterReload > 0 ? await page.getByTestId("last-run-hint").innerText() : "");
  expect(hintAfterReload).toBe(0);

  // 点「新建会话」→ 一切提示都应清空
  await page.getByTitle("新建会话").click();
  await page.waitForTimeout(800);
  expect(await page.getByTestId("last-run-hint").count()).toBe(0);
  expect(await page.getByTestId("run-notice").count()).toBe(0);
  await page.screenshot({ path: "../docs/acceptance/task-23-new-session-clean.png" });
});
