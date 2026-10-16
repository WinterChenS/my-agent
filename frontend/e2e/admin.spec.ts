import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * 管理台页面浏览器验收（技术方案第 7 节 + Task 6.1 的「前端 MCP 管理页」门禁）。
 *
 * 设计原则：**测试必须自给自足**——自己造带唯一名称的 fixture，结束后清理，
 * 绝不断言用户自己维护的数据（踩过坑：同事把演示供应商改名后测试就红了）。
 */

const API = "http://localhost:8080";

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  const body = await res.json();
  return body.data.token as string;
}

async function apiCleanup(request: APIRequestContext, prefix: string) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };

  const del = async (path: string, id: number) => {
    await request.delete(`${API}${path}/${id}`, { headers, failOnStatusCode: false });
  };

  // 先算出“属于本次测试”的供应商 id（必须在删模型之前算）
  const providers = await (await request.get(`${API}/api/model-providers?current=1&size=500`, { headers })).json();
  const e2eProviderIds = new Set<number>(
    (providers.data?.records ?? [])
      .filter((p: { name: string }) => String(p.name).startsWith(prefix))
      .map((p: { id: number }) => p.id),
  );

  // 顺序：agent -> session -> mcp/skill -> model -> provider（先解绑定）
  const agents = await (await request.get(`${API}/api/agents?current=1&size=500`, { headers })).json();
  for (const a of agents.data?.records ?? []) {
    if (String(a.agentId).startsWith(prefix)) await del("/api/agents", a.id);
  }
  const mcp = await (await request.get(`${API}/api/mcp-servers?current=1&size=500`, { headers })).json();
  for (const m of mcp.data?.records ?? []) {
    if (String(m.name).startsWith(prefix)) await del("/api/mcp-servers", m.id);
  }
  const skills = await (await request.get(`${API}/api/skills?current=1&size=500`, { headers })).json();
  for (const s of skills.data?.records ?? []) {
    if (String(s.name).startsWith(prefix)) await del("/api/skills", s.id);
  }
  const models = await (await request.get(`${API}/api/models?current=1&size=500`, { headers })).json();
  for (const m of models.data?.records ?? []) {
    // 也删掉“属于 e2e 供应商”的模型：用例可能把模型改名成不含 e2e- 的值
    const ownedByE2eProvider = e2eProviderIds.has(Number(m.providerId));
    if (String(m.modelId).startsWith(prefix) || ownedByE2eProvider) await del("/api/models", m.id);
  }
  for (const id of e2eProviderIds) await del("/api/model-providers", id);
}

/** 每个用例结束都清理自己造的数据。 */
test.afterEach(async ({ request }) => {
  await apiCleanup(request, "e2e-");
});

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });
}

/** 在模型页新建一个指向本地桩的供应商 + 指定模型，返回两者的名称。 */
async function createProviderAndModel(
  page: import("@playwright/test").Page,
  stamp: number,
  modelId: string,
  maxTokens?: string,
) {
  const providerName = `e2e-prov-${stamp}`;
  await page.getByRole("button", { name: "新建供应商", exact: true }).click();
  await page.getByLabel("名称").fill(providerName);
  await page.getByLabel("Base URL").fill("http://localhost:8099/v1");
  await page.getByLabel(/API Key/).fill("sk-e2e");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByText("已创建供应商")).toBeVisible({ timeout: 20_000 });

  await page.getByRole("tab", { name: /模型/ }).click();
  await page.getByRole("button", { name: "新建模型", exact: true }).click();
  await page.getByLabel("模型 ID").fill(modelId);
  await page.getByLabel("显示名").fill(modelId);
  if (maxTokens) {
    await page.getByLabel(/最大输出 tokens/).fill(maxTokens);
  }
  await page.getByRole("dialog").locator("select").first().selectOption({ label: providerName });
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByText("已创建模型")).toBeVisible({ timeout: 20_000 });

  return { providerName, modelId };
}

test("MCP 管理页：列表 / 新建 STDIO / 同步工具 / 工具清单 / 删除", async ({ page }) => {
  await login(page);
  await page.goto("/admin/mcp");
  await expect(page.getByRole("heading", { name: "MCP 配置" })).toBeVisible({ timeout: 15_000 });

  const name = `e2e-mcp-${Date.now()}`;
  await page.getByRole("button", { name: "新建", exact: true }).click();
  // 注意：页面上新增了筛选栏（含「名称包含」），所以要限定在弹窗里找「名称」
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("名称").fill(name);
  await page.getByLabel("命令").fill("cmd");
  await page
    .getByLabel("参数（每行一个）")
    .fill("/c\nnpx\n-y\n@modelcontextprotocol/server-everything\nstdio");
  await page.getByRole("button", { name: "保存", exact: true }).click();

  const row = page.getByRole("row", { name: new RegExp(name) });
  await expect(row).toBeVisible({ timeout: 20_000 });

  // 同步工具 -> 工具清单
  // 注意：这个用例依赖 `npx -y @modelcontextprotocol/server-everything`，首次需要联网拉包，
  // 偶发超出 60s（同一套件里第二次跑就遇到过）。同步是幂等的，失败就再点一次。
  const syncButton = row.getByRole("button", { name: "同步工具", exact: true });
  await syncButton.click();
  try {
    await expect(page.getByText(/同步成功：\d+ 个工具/)).toBeVisible({ timeout: 60_000 });
  } catch {
    await syncButton.click();
    await expect(page.getByText(/同步成功：\d+ 个工具/)).toBeVisible({ timeout: 120_000 });
  }
  await expect(page.getByText(new RegExp(`工具清单：${name}`))).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole("cell", { name: "echo", exact: true })).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-repair-mcp-page.png", fullPage: true });

  page.on("dialog", (d) => d.accept());
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await expect(page.getByText("已删除")).toBeVisible({ timeout: 20_000 });
});

test("模型页：自建供应商 + 连通测试 + 模型列表 + api_key 掩码", async ({ page }) => {
  await login(page);
  await page.goto("/admin/models");
  await expect(page.getByRole("heading", { name: "模型配置" })).toBeVisible({ timeout: 15_000 });

  const stamp = Date.now();
  const { providerName } = await createProviderAndModel(page, stamp, `e2e-ok-${stamp}`);

  // 掩码：只断言存在 **** 单元格（不断言具体是哪一行，避免依赖用户数据）
  await page.getByRole("tab", { name: /供应商/ }).click();
  await expect(page.getByRole("cell", { name: "****" }).first()).toBeVisible({ timeout: 15_000 });

  // 对自己建的供应商点「测试全部」（逐个模型测）
  const row = page.getByRole("row", { name: new RegExp(providerName) });
  await row.getByRole("button", { name: /测试全部/ }).click();
  await expect(page.getByText(/全部通过/)).toBeVisible({ timeout: 60_000 });

  // 模型 tab 能看到自己建的模型（displayName 与 modelId 同名，会命中 2 个单元格，取 first）
  await page.getByRole("tab", { name: /模型/ }).click();
  await expect(
    page.getByRole("cell", { name: `e2e-ok-${stamp}` }).first(),
  ).toBeVisible({ timeout: 15_000 });

  await page.screenshot({ path: "../docs/acceptance/task-repair-models-page.png", fullPage: true });
});

test("模型页：模型 ID 填错时给出可操作的提示，改对后测通", async ({ page }) => {
  await login(page);
  await page.goto("/admin/models");
  await expect(page.getByRole("heading", { name: "模型配置" })).toBeVisible({ timeout: 15_000 });

  const stamp = Date.now();
  const badId = `e2e-wrong-model-${stamp}`; // 桩对含 "wrong-model" 的名字回 404
  const goodId = `e2e-good-model-${stamp}`; // 桩对其它名字正常响应
  await createProviderAndModel(page, stamp, badId);

  // 单测这个模型 -> 应看到可操作提示
  const row = page.getByRole("row", { name: new RegExp(badId) });
  await row.getByRole("button", { name: "测试", exact: true }).click();
  await expect(page.getByText(/连通失败/)).toBeVisible({ timeout: 40_000 });
  await expect(page.getByText(/可能不被该端点支持/)).toBeVisible({ timeout: 20_000 });
  // 网关原始错误体也应一并展示（用不会与用户数据撞车的片段断言）
  await expect(page.getByText(/knows no model/).first()).toBeVisible();
  await page.screenshot({
    path: "../docs/acceptance/task-repair-model-id-hint.png",
    fullPage: true,
  });

  // 改成可用 ID -> 测通
  await row.getByRole("button", { name: "编辑", exact: true }).click();
  await page.getByLabel("模型 ID").fill(goodId);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByText("已保存")).toBeVisible({ timeout: 20_000 });

  const fixedRow = page.getByRole("row", { name: new RegExp(goodId) });
  await fixedRow.getByRole("button", { name: "测试", exact: true }).click();
  await expect(page.getByText(/连通成功/)).toBeVisible({ timeout: 40_000 });
});

test("模型页：模型 ID 含斜杠（如 deepseek/xxx）能正确保存与测试", async ({ page }) => {
  await login(page);
  await page.goto("/admin/models");
  await expect(page.getByRole("heading", { name: "模型配置" })).toBeVisible({ timeout: 15_000 });

  const stamp = Date.now();
  const slashId = `deepseek/deepseek-flash-${stamp}`;
  await createProviderAndModel(page, stamp, `e2e-base-${stamp}`);

  // 点模型 ID 单元格 -> 改成带斜杠的 ID -> 保存 -> 能测通
  await page
    .getByRole("row", { name: new RegExp(`e2e-base-${stamp}`) })
    .getByRole("cell", { name: `e2e-base-${stamp}` })
    .first()
    .click();
  await page.getByLabel("模型 ID").fill(slashId);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByText("已保存")).toBeVisible({ timeout: 20_000 });

  const row = page.getByRole("row", { name: new RegExp(slashId.replace("/", "\\/")) });
  await expect(row.first()).toBeVisible({ timeout: 15_000 });
  await row.first().getByRole("button", { name: "测试", exact: true }).click();
  await expect(page.getByText(/连通成功/)).toBeVisible({ timeout: 40_000 });

  await page.screenshot({
    path: "../docs/acceptance/task-repair-models-tab.png",
    fullPage: true,
  });
});

test("模型页：max_tokens 超出厂商范围时，测试阶段就报错（用户实测场景）", async ({ page }) => {
  await login(page);
  await page.goto("/admin/models");
  await expect(page.getByRole("heading", { name: "模型配置" })).toBeVisible({ timeout: 15_000 });

  const stamp = Date.now();
  const modelId = `e2e-maxtok-${stamp}`;

  // 新建时填一个“像上下文长度”的值：界面应立刻给提醒
  await page.getByRole("button", { name: "新建供应商", exact: true }).click();
  await page.getByLabel("名称").fill(`e2e-prov-${stamp}`);
  await page.getByLabel("Base URL").fill("http://localhost:8099/v1");
  await page.getByLabel(/API Key/).fill("sk-e2e");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByText("已创建供应商")).toBeVisible({ timeout: 20_000 });

  await page.getByRole("tab", { name: /模型/ }).click();
  await page.getByRole("button", { name: "新建模型", exact: true }).click();
  await page.getByLabel("模型 ID").fill(modelId);
  await page.getByLabel(/最大输出 tokens/).fill("1000000");
  // 界面警示（不靠外部知识就知道填错了）
  await expect(page.getByText(/看起来像“上下文长度”/)).toBeVisible({ timeout: 10_000 });
  await page.getByRole("dialog").locator("select").first().selectOption({ label: `e2e-prov-${stamp}` });
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByText("已创建模型")).toBeVisible({ timeout: 20_000 });

  // 测试：此时必须暴露真实的非法配置（v1 因探针硬写 max_tokens=8 而给了假绿灯）
  const row = page.getByRole("row", { name: new RegExp(modelId) });
  await row.getByRole("button", { name: "测试", exact: true }).click();
  await expect(page.getByText(/valid range|Invalid max_tokens/)).toBeVisible({ timeout: 40_000 });
  await page.screenshot({
    path: "../docs/acceptance/task-repair-maxtokens-hint.png",
    fullPage: true,
  });

  // 改成合理值 -> 测通
  await row.getByRole("button", { name: "编辑", exact: true }).click();
  await page.getByLabel(/最大输出 tokens/).fill("8192");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByText("已保存")).toBeVisible({ timeout: 20_000 });
  await row.getByRole("button", { name: "测试", exact: true }).click();
  await expect(page.getByText(/连通成功/)).toBeVisible({ timeout: 40_000 });
});

test("Skill 页：新建 / 版本显示 / 删除", async ({ page }) => {
  await login(page);
  await page.goto("/admin/skills");
  await expect(page.getByRole("heading", { name: "Skill 配置" })).toBeVisible({ timeout: 15_000 });

  const name = `e2e-skill-${Date.now()}`;
  await page.getByRole("button", { name: "新建", exact: true }).click();
  await page.getByLabel("名称").fill(name);
  await page.getByLabel("说明").fill("E2E 创建");
  // 内容编辑器现在是所见即所得的 MDXEditor（contentEditable），往里打字即可
  const editor = page.getByTestId("skill-wysiwyg").locator('[contenteditable="true"]');
  await editor.click();
  await page.keyboard.type("回答时在末尾加【OK】。");
  await page.getByRole("button", { name: "保存", exact: true }).click();

  const row = page.getByRole("row", { name: new RegExp(name) });
  await expect(row).toBeVisible({ timeout: 20_000 });
  await expect(row.getByText("v1")).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-repair-skills-page.png", fullPage: true });

  page.on("dialog", (d) => d.accept());
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await expect(page.getByText("已删除")).toBeVisible({ timeout: 20_000 });
});

test("Agent 页：可创建带绑定的 Agent 并展示权限模式", async ({ page, request }) => {
  // 用 API 直接造一个 e2e Agent（避免依赖用户数据），再在页面断言
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();

  const prov = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `e2e-prov-${stamp}`,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl: "http://localhost:8099/v1",
        apiKey: "sk-e2e",
        status: "ENABLED",
      },
    })
  ).json();
  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: { providerId: prov.data.id, modelId: `e2e-ok-${stamp}`, status: "ENABLED" },
    })
  ).json();
  await request.post(`${API}/api/agents`, {
    headers,
    data: {
      name: `e2e-agent-${stamp}`,
      agentId: `e2e-agent-${stamp}`,
      sysPrompt: "测试",
      modelId: model.data.id,
      permissionMode: "REQUIRE_APPROVAL",
      status: "ENABLED",
      skillIds: [],
      mcpServerIds: [],
    },
  });

  await login(page);
  await page.goto("/admin/agents");
  await expect(page.getByRole("heading", { name: "Agent 配置" })).toBeVisible({ timeout: 15_000 });

  const row = page.getByRole("row", { name: new RegExp(`e2e-agent-${stamp}`) });
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText("需要审批（HITL）")).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-repair-agents-page.png", fullPage: true });
});
