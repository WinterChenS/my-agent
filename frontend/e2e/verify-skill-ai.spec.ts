import { expect, test } from "@playwright/test";

/** 用户需求：技能创建可以用 AI —— 拿到工具清单与格式要求，按自然语言生成并自动填入编辑器。 */
test("用 AI 生成技能并自动填入编辑器", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 950 });
  const stamp = Date.now();

  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/skills");
  await page.getByRole("button", { name: /新建/ }).click();
  await page.getByTestId("skill-ai-panel").waitFor({ timeout: 20000 });

  // 面板上应写明交给模型的工具数量（本地 N + MCP M）
  console.log("[① AI 面板]", (await page.getByTestId("skill-ai-panel").innerText()).replace(/\s+/g, " ").slice(0, 90));
  console.log("[① 使用的模型]", await page.getByTestId("skill-ai-model").locator("option:checked").innerText());
  await expect(page.getByTestId("skill-ai-panel")).toContainText("本地");

  // 输入需求 → 生成
  await page
    .getByTestId("skill-ai-requirement")
    .fill("当用户询问退款进度时，先确认订单号再查退款状态；查不到就让他核对订单号。");
  await page.getByTestId("skill-ai-generate").click();

  // 生成较慢（真实模型），给足时间；等名称被填上
  const nameInput = page.locator('input[placeholder="reply-with-ok"]');
  await expect(nameInput).not.toHaveValue("", { timeout: 180000 });
  const name = await nameInput.inputValue();
  const description = await page.getByTestId("skill-description").inputValue();
  const editorText = await page.locator('[contenteditable="true"]').first().innerText();

  console.log("[② name]", name);
  console.log("[③ description]", description.slice(0, 100));
  console.log("[④ 编辑器正文前 120]", editorText.replace(/\s+/g, " ").slice(0, 120));

  expect(name.length).toBeGreaterThan(0);
  expect(description.length).toBeGreaterThan(0);
  expect(editorText.length).toBeGreaterThan(30);
  // 正文里应出现某个真实工具名（证明它拿着工具清单在写）
  const toolMentioned = ["session_search", "memory_search", "demo_confirm_card", "demo_mapping_card"].some((t) =>
    editorText.includes(t),
  );
  console.log("[⑤ 正文引用了真实工具]", toolMentioned);
  expect(toolMentioned).toBe(true);

  // 清掉刚生成的 name，避免和别的技能重名干扰（本用例不保存）
  await nameInput.fill(`ai-e2e-${stamp}`);
  await page.screenshot({ path: "../docs/acceptance/task-32-skill-ai-generate.png" });
  await page.getByRole("button", { name: "取消" }).click();
});
