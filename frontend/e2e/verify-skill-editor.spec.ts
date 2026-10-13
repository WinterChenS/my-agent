import { expect, test } from "@playwright/test";

/** 用户需求：① 技能列表分页 ② 编辑器支持 Markdown 预览 ③ 输入 "/" 唤起 MCP/本地工具插入。 */
test("技能页：分页 + Markdown 预览 + / 插入工具", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/skills");
  await expect(page.getByTestId("skills-pagination")).toBeVisible({ timeout: 20000 });
  const summary = await page.getByTestId("skills-pagination").innerText();
  console.log("[分页]", summary.replace(/\s+/g, " "));
  await expect(page.getByTestId("skills-page-size")).toBeVisible();

  // 换每页条数 → 页数随之变化
  await page.getByTestId("skills-page-size").selectOption("20");
  await page.waitForTimeout(800);
  console.log("[分页·每页20]", (await page.getByTestId("skills-pagination").innerText()).replace(/\s+/g, " "));

  // 打开编辑弹窗（第一条的编辑按钮）
  await page.locator('[title="编辑"]').first().click();
  await expect(page.getByTestId("skill-content-input")).toBeVisible({ timeout: 10000 });
  await expect(page.getByTestId("skill-content-preview")).toBeVisible();
  const md = "# 标题\n\n- 列表项\n\n| a | b |\n| - | - |\n| 1 | 2 |\n";
  await page.getByTestId("skill-content-input").fill(md);
  await page.waitForTimeout(400);
  const preview = await page.getByTestId("skill-content-preview").innerText();
  console.log("[预览] 渲染文本 =", preview.replace(/\s+/g, " ").slice(0, 80));
  expect(preview).toContain("标题");
  expect(preview).toContain("列表项");
  await expect(page.getByTestId("skill-content-preview").locator("table")).toHaveCount(1);

  // 输入 "/" 唤起工具菜单
  await page.getByTestId("skill-content-input").fill(md + "\n调用 /");
  await expect(page.getByTestId("skill-tool-menu")).toBeVisible({ timeout: 10000 });
  const optionCount = await page.getByTestId("skill-tool-option").count();
  console.log("[工具菜单] 候选数 =", optionCount);
  const first = await page.getByTestId("skill-tool-option").first().innerText();
  console.log("[工具菜单] 第一项 =", first.replace(/\s+/g, " "));
  expect(optionCount).toBeGreaterThan(0);

  // 过滤到本地工具，确认本地工具也在列表里
  await page.getByTestId("skill-content-input").fill(md + "\n调用 /read");
  await page.waitForTimeout(400);
  const menuText = await page.getByTestId("skill-tool-menu").innerText();
  console.log("[工具菜单] 过滤 read →", menuText.replace(/\s+/g, " ").slice(0, 120));
  expect(menuText).toContain("read_file");

  // 选中插入
  await page.getByTestId("skill-tool-option").first().click();
  const after = await page.getByTestId("skill-content-input").inputValue();
  console.log("[插入结果] 末尾 =", after.slice(-40).replace(/\n/g, "\n"));
  expect(after).toContain("read_file");
  await page.screenshot({ path: "../docs/acceptance/task-25-skill-editor.png" });
});
