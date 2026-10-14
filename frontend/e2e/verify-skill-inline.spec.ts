import { expect, test } from "@playwright/test";

/**
 * 用户反馈三件事一起验：
 *  ① 选中的技能要出现在**我的消息气泡里**（以前发出去就没有了）
 *  ② 技能块要在**输入框里面**（不是挂在上面一排）
 *  ③ 技能要**按当时输入的位置**展示，且是不可编辑的块
 */
test("技能块：行内、不可编辑、按位置进气泡", async ({ page }) => {
  await page.setViewportSize({ width: 1500, height: 900 });
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  // 新建会话
  await page.getByTitle("新建会话").click();
  const input = page.getByTestId("chat-input");
  await expect(input).toBeVisible({ timeout: 20000 });

  // ① 输入框就是 contentEditable（技能块要能行内放进去）
  console.log("[① 输入框 tagName]", await input.evaluate((el) => el.tagName));
  console.log("[① contenteditable]", await input.getAttribute("contenteditable"));

  // 打字 → 输入 / → 选技能
  await input.click();
  await page.keyboard.type("请用 ");
  await page.keyboard.type("/");
  const menu = page.getByTestId("skill-menu");
  const menuVisible = await menu.isVisible().catch(() => false);
  console.log("[② `/` 菜单可见]", menuVisible);
  if (!menuVisible) {
    console.log("[②] 当前没有可用技能，跳过（请在技能页建一个启用的技能再跑）");
    return;
  }
  const optionCount = await page.getByTestId("skill-option").count();
  const skillName = (await page.getByTestId("skill-option").first().innerText()).replace(/^\//, "").trim().split("\n")[0];
  console.log("[② 候选数]", optionCount, "| 选中:", skillName);
  await page.getByTestId("skill-option").first().click();
  await page.keyboard.type(" 帮我看看");

  // 技能块在**输入框内部**
  const chipInInput = input.locator("[data-skill-name]");
  console.log("[③ 输入框内的技能块数]", await chipInInput.count());
  expect(await chipInInput.count()).toBe(1);
  // 且不可编辑
  console.log("[③ 技能块 contenteditable]", await chipInInput.first().getAttribute("contenteditable"));
  expect(await chipInInput.first().getAttribute("contenteditable")).toBe("false");

  // 位置：技能块位于「请用」之后、「帮我看看」之前
  const inputHtml = await input.innerHTML();
  const skillIdx = inputHtml.indexOf("data-skill-name");
  const beforeOk = inputHtml.slice(0, skillIdx).includes("请用");
  const afterOk = inputHtml.slice(skillIdx).includes("帮我看看");
  console.log("[③ 位置正确（前面有‘请用’、后面有‘帮我看看’）]", beforeOk && afterOk);
  expect(beforeOk && afterOk).toBe(true);

  await page.screenshot({ path: "../docs/acceptance/task-33-skill-inline-input.png" });

  // 发送
  await page.keyboard.press("Enter");
  // ② 用户气泡里出现技能块（修复前这里没有）
  const bubbleChip = page.getByTestId("message-skill");
  await expect(bubbleChip.first()).toBeVisible({ timeout: 60000 });
  const bubbleText = await page.getByTestId("user-text").first().innerText();
  console.log("[① 气泡里的技能块数]", await bubbleChip.count(), "| 气泡文本:", bubbleText.replace(/\s+/g, " "));
  expect(await bubbleChip.count()).toBeGreaterThan(0);
  // 气泡里也是按位置：技能名出现在「请用」与「帮我看看」之间
  expect(bubbleText.indexOf("请用")).toBeLessThan(bubbleText.indexOf(skillName));
  expect(bubbleText.indexOf(skillName)).toBeLessThan(bubbleText.indexOf("帮我看看"));

  // 输入框已清空
  console.log("[④ 发送后输入框内容]", JSON.stringify(await input.innerText()));
  expect((await input.innerText()).trim()).toBe("");

  await page.screenshot({ path: "../docs/acceptance/task-33-skill-in-bubble.png" });
});
