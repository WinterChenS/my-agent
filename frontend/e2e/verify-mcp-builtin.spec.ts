import { expect, test } from "@playwright/test";

/** 用户反馈：MCP 页看不到平台自身的内置工具（read_file / memory_* / agent_* …）。 */
test("MCP 页展示平台内置工具（只读）", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/mcp");
  const card = page.getByTestId("builtin-tools-card");
  await expect(card).toBeVisible({ timeout: 25000 });
  await expect(page.getByTestId("builtin-tool-row").first()).toBeVisible({ timeout: 25000 });

  const rows = await page.getByTestId("builtin-tool-row").count();
  const names = await page.getByTestId("builtin-tool-row").evaluateAll((els) =>
    els.map((el) => el.querySelector("td")?.textContent?.trim() ?? ""),
  );
  console.log("[内置工具] 行数 =", rows);
  console.log("[内置工具] 名单 =", names.join(", "));
  expect(rows).toBeGreaterThan(0);
  // 这几个是框架注册的典型内置工具
  expect(names).toContain("read_file");
  expect(names.some((n) => n.startsWith("memory_"))).toBe(true);
  expect(card).toContainText("AgentScope");

  await page.screenshot({ path: "../docs/acceptance/task-28-mcp-builtin-tools.png", fullPage: true });
});
