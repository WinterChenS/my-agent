import { expect, test } from "@playwright/test";

/**
 * 管理台四个页面（Task 6.1 的「前端 MCP 管理页」门禁 + 技术方案第 7 节的 models/agents/skills）。
 * 前置：后端与 Vite 已启动；已存在一个 provider/model/agent（见 scripts/add-real-model.sh）。
 */

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });
}

test("MCP 管理页：列表 / 新建 STDIO / 同步工具 / 工具清单 / 删除", async ({ page }) => {
  await login(page);
  await page.goto("/admin/mcp");
  await expect(page.getByRole("heading", { name: "MCP 配置" })).toBeVisible({ timeout: 15_000 });

  const name = `e2e-mcp-${Date.now()}`;
  await page.getByRole("button", { name: "新建" }).click();
  await page.getByLabel("名称").fill(name);
  await page.getByLabel("命令").fill("cmd");
  await page.getByLabel("参数（每行一个）").fill("/c\nnpx\n-y\n@modelcontextprotocol/server-everything\nstdio");
  await page.getByRole("button", { name: "保存" }).click();

  // 列表出现
  const row = page.getByRole("row", { name: new RegExp(name) });
  await expect(row).toBeVisible({ timeout: 20_000 });

  // 同步工具 -> 出现工具数
  await row.getByRole("button", { name: /同步工具/ }).click();
  await expect(page.getByText(/同步成功：\d+ 个工具/)).toBeVisible({ timeout: 60_000 });

  // 工具清单区块出现且含 echo
  await expect(page.getByText(new RegExp(`工具清单：${name}`))).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole("cell", { name: "echo", exact: true })).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-repair-mcp-page.png", fullPage: true });

  // 删除
  page.on("dialog", (d) => d.accept());
  await row.getByRole("button", { name: "删除" }).click();
  await expect(page.getByText("已删除")).toBeVisible({ timeout: 20_000 });
});

test("模型页：供应商列表 / 连通测试 / 模型列表", async ({ page }) => {
  await login(page);
  await page.goto("/admin/models");
  await expect(page.getByRole("heading", { name: "模型配置" })).toBeVisible({ timeout: 15_000 });

  // 供应商 tab 默认打开，应能看到演示供应商
  await expect(page.getByRole("cell", { name: "本地桩(演示)" })).toBeVisible({ timeout: 15_000 });
  // api_key 必须是掩码
  await expect(page.getByRole("cell", { name: "****" }).first()).toBeVisible();

  await page.getByRole("button", { name: /连通测试/ }).first().click();
  await expect(page.getByText(/连通成功：\d+ ms/)).toBeVisible({ timeout: 40_000 });

  await page.getByRole("tab", { name: /模型/ }).click();
  await expect(page.getByRole("cell", { name: "stub-model" }).first()).toBeVisible({ timeout: 15_000 });

  await page.screenshot({ path: "../docs/acceptance/task-repair-models-page.png", fullPage: true });
});

test("Skill 页：新建 / 版本显示 / 删除", async ({ page }) => {
  await login(page);
  await page.goto("/admin/skills");
  await expect(page.getByRole("heading", { name: "Skill 配置" })).toBeVisible({ timeout: 15_000 });

  const name = `e2e-skill-${Date.now()}`;
  await page.getByRole("button", { name: "新建" }).click();
  await page.getByLabel("名称").fill(name);
  await page.getByLabel("说明").fill("E2E 创建");
  await page.getByLabel("内容（Markdown）").fill("回答时在末尾加【OK】。");
  await page.getByRole("button", { name: "保存" }).click();

  const row = page.getByRole("row", { name: new RegExp(name) });
  await expect(row).toBeVisible({ timeout: 20_000 });
  await expect(row.getByText("v1")).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-repair-skills-page.png", fullPage: true });

  page.on("dialog", (d) => d.accept());
  await row.getByRole("button", { name: "删除" }).click();
  await expect(page.getByText("已删除")).toBeVisible({ timeout: 20_000 });
});

test("Agent 页：列表展示绑定与权限模式", async ({ page }) => {
  await login(page);
  await page.goto("/admin/agents");
  await expect(page.getByRole("heading", { name: "Agent 配置" })).toBeVisible({ timeout: 15_000 });

  const row = page.getByRole("row", { name: /stub-demo-agent/ });
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText("直接执行")).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-repair-agents-page.png", fullPage: true });
});
