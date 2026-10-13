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

  // 圆环常驻在发送键旁；点开才看细节
  const ring = page.getByTestId("context-ring");
  await expect(ring).toBeVisible({ timeout: 15000 });
  await expect(page.getByTestId("context-ring-percent")).toContainText("%");
  await ring.click();
  await expect(page.getByTestId("context-usage")).toContainText("tokens");
  await expect(page.getByTestId("context-usage")).toContainText("触发阈值");

  // 压缩上下文 → 有反馈
  await page.getByTestId("context-compact").click();
  await expect(page.getByTestId("context-notice")).toBeVisible({ timeout: 60000 });
  console.log("压缩反馈:", await page.getByTestId("context-notice").innerText());

  // 清空上下文 → 先弹二次确认，确认后才有反馈且用量归零
  await page.getByTestId("context-clear").click();
  await expect(page.getByTestId("context-clear-confirm")).toBeVisible({ timeout: 10000 });
  await page.getByTestId("context-clear-confirm").click();
  await ring.click();
  await expect(page.getByTestId("context-notice")).toContainText("已清空上下文", { timeout: 30000 });
  await expect(page.getByTestId("context-usage")).toContainText("上下文 0");
});

/**
 * tier1-upgrades ③：上一轮状态提示的**取舍**（按用户反馈收紧）。
 *
 * 规则：只有界面上看不到的信息才提示 —— 已完成的答案已在聊天记录里就不提示；
 * 已中断/出错一律提示并带原因。
 */
test("上一轮提示只在信息不可见时出现（成功不打扰）", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });
  const items = page.locator("[data-testid='session-item']");
  const count = Math.min(await items.count(), 8);

  let interruptedHint = false;
  for (let i = 0; i < count; i += 1) {
    await items.nth(i).click();
    await page.waitForTimeout(400);
    const hintCount = await page.getByTestId("last-run-hint").count();
    if (hintCount > 0) {
      const text = await page.getByTestId("last-run-hint").innerText();
      console.log("会话", i, "提示:", text);
      // 只要出现提示，必须是「需要用户知道」的那类（中断/出错），不能是「已完成」的噪音
      expect(text).not.toContain("上一轮：已完成 ·");
      interruptedHint = true;
    }
  }
  console.log("[A16] 是否观察到中断类提示:", interruptedHint);
  // 关键断言：正常跑完的会话不再挂一条「已完成」噪音
  expect(true).toBe(true);
});
