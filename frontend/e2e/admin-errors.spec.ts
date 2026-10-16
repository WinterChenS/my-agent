import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * 管理台**错误分支**的页面级覆盖（B4）。
 *
 * 为什么要它：此前管理台只有 CRUD happy path 的页面级覆盖，各类校验（非法参数、重名、被引用时不能删）
 * 只在后端单测里验过 —— 但用户看到的是**页面上的提示**，所以「错误有没有正确显示给用户」必须走页面验一遍。
 *
 * 自给自足：自己造供应商/模型/Agent/技能/MCP（名字统一 `e2e-err-` 前缀），跑完删干净；
 * 不依赖库里恰好有什么，也不碰用户自己的数据。
 */
const API = "http://localhost:8080";
const STUB = "http://localhost:8099/v1";

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  return (await res.json()).data.token as string;
}

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.waitForURL(/\/chat/, { timeout: 20_000 });
}

test.describe("管理台错误分支（页面级）", () => {
  test.setTimeout(180_000);

  let headers: Record<string, string> = {};
  let providerId = 0;
  const createdAgents: number[] = [];
  const createdSkills: number[] = [];
  const createdMcps: number[] = [];

  test.beforeEach(async ({ request, page }) => {
    // 造数据用接口（快），但**页面也要登录**：这些用例是走 UI 的
    await login(page);
    const token = await apiToken(request);
    headers = { Authorization: `Bearer ${token}` };
    const stamp = Date.now();
    const provider = await (
      await request.post(`${API}/api/model-providers`, {
        headers,
        data: {
          name: `e2e-err-prov-${stamp}`,
          providerType: "OPENAI_COMPATIBLE",
          baseUrl: STUB,
          apiKey: "stub-key",
          status: "ENABLED",
        },
      })
    ).json();
    providerId = provider.data.id as number;
  });

  test.afterEach(async ({ request }) => {
    // 先删会拦住后续删除的东西：Agent → 技能/MCP → 供应商
    for (const id of createdAgents.splice(0)) {
      await request.delete(`${API}/api/agents/${id}`, { headers });
    }
    for (const id of createdSkills.splice(0)) {
      await request.delete(`${API}/api/skills/${id}`, { headers });
    }
    for (const id of createdMcps.splice(0)) {
      await request.delete(`${API}/api/mcp-servers/${id}`, { headers });
    }
    const models = await (await request.get(`${API}/api/models?current=1&size=200`, { headers })).json();
    for (const m of models.data.records as { id: number; providerId: number }[]) {
      if (m.providerId === providerId) {
        await request.delete(`${API}/api/models/${m.id}`, { headers });
      }
    }
    await request.delete(`${API}/api/model-providers/${providerId}`, { headers });
  });

  /** 打开「新建模型」弹窗并填好基础字段（供应商已选中）。 */
  async function openModelForm(page: Page, modelId: string) {
    await page.goto("/admin/models");
    await page.getByRole("tab", { name: "模型" }).click();
    await page.getByRole("button", { name: "新建模型", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 15_000 });
    // 供应商默认选第一个；显式选到我们造的那个
    // 「供应商」在页面上有两处（表格表头 + 弹窗内的下拉），限定在弹窗里
    await dialog.getByLabel("供应商").selectOption(String(providerId));
    await page.getByLabel("模型 ID").fill(modelId);
    return dialog;
  }

  test("模型页：非法参数各自给出明确提示且不写入", async ({ page }) => {
    const stamp = Date.now();
    const cases: [string, string, string, RegExp][] = [
      ["温度", "3", "温度 temperature", /温度必须在 0–2 之间/],
      ["Top-P", "0", "Top-N", /Top-P 必须在 \(0, 1\] 之间/],
      ["上下文长度", "0", "上下文长度", /上下文长度必须大于 0/],
      ["输出长度", "0", "最大输出", /输出长度必须大于 0/],
      ["价格为负", "-1", "输入价格", /价格不能为负数/],
    ];
    for (const [label, value, field, expected] of cases) {
      const modelId = `e2e-err-model-${label}-${stamp}`;
      const dialog = await openModelForm(page, modelId);
      await page.getByLabel(field).fill(value);
      await dialog.getByRole("button", { name: "保存", exact: true }).click();
      await expect(page.getByText(expected).first(), `${label} 应给出明确提示`).toBeVisible({
        timeout: 15_000,
      });
      console.log(`  [${label}] 提示:`, expected.source);
      // 弹窗不该关闭（说明没写进去）
      await expect(dialog).toBeVisible();
      await dialog.getByRole("button", { name: "取消", exact: true }).click();
      await page.waitForTimeout(300);
    }
    // 确认没有任何一条落库
    const list = await (await page.request.get(`${API}/api/models?current=1&size=200`, { headers })).json();
    const leaked = (list.data.records as { modelId: string }[]).filter((m) =>
      m.modelId.startsWith(`e2e-err-model-`),
    );
    console.log("  非法参数落库条数（应为 0）:", leaked.length);
    expect(leaked.length).toBe(0);
  });

  test("模型页：输出长度大于上下文长度被拦下（前后端都提示）", async ({ page }) => {
    const stamp = Date.now();
    const dialog = await openModelForm(page, `e2e-err-model-len-${stamp}`);
    await page.getByLabel("上下文长度").fill("1000");
    await page.getByLabel("最大输出").fill("5000");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    // 前端会先拦一次（文案不带数值），后端也会给带数值的文案；任一出现即可
    await expect(page.getByText(/输出长度不能大于上下文长度|输出长度（5000）不能大于上下文长度（1000）/).first()).toBeVisible({
      timeout: 15_000,
    });
    console.log("  提示已出现（前端或后端）");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
  });

  test("模型页：同一供应商下同名模型被拒", async ({ page }) => {
    const stamp = Date.now();
    const modelId = `e2e-err-dup-${stamp}`;
    // 先用接口建一个
    await page.request.post(`${API}/api/models`, {
      headers,
      data: { providerId, modelId, status: "ENABLED" },
    });
    const dialog = await openModelForm(page, modelId);
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/该供应商下已存在同名模型/)).toBeVisible({ timeout: 15_000 });
    console.log("  重名提示已出现");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
  });

  test("Agent 页：重名 / 非法 temperature / 非法 maxTokens 各自提示", async ({ page }) => {
    const stamp = Date.now();
    const agentId = `e2e-err-agent-${stamp}`;
    // 先用接口建一个同名 Agent
    const model = await (
      await page.request.post(`${API}/api/models`, {
        headers,
        data: { providerId, modelId: `e2e-err-amodel-${stamp}`, status: "ENABLED" },
      })
    ).json();

    const openAgentForm = async () => {
      await page.goto("/admin/agents");
      await page.getByRole("button", { name: "新建", exact: true }).click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toBeVisible({ timeout: 15_000 });
      return dialog;
    };
    // 1) 重名
    const created = await (
      await page.request.post(`${API}/api/agents`, {
        headers,
        data: {
          name: agentId,
          agentId,
          sysPrompt: "你是助手。",
          modelId: model.data.id,
          permissionMode: "ALWAYS_ALLOW",
          maxIterations: 2,
          status: "ENABLED",
          skillIds: [],
          mcpServerIds: [],
        },
      })
    ).json();
    createdAgents.push(created.data.id as number);

    let dialog = await openAgentForm();
    // 注意：表单有**两个**名字字段 —— `名称`（显示名，重名检查看的是它）与 `agentId`（唯一标识）
    await page.getByLabel("名称", { exact: true }).fill(agentId);
    await page.getByLabel(/agentId/).fill(agentId);
    await page.getByLabel("系统提示词").fill("你是助手。");
    await page.getByLabel("模型").selectOption(String(model.data.id));
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/Agent 名称已存在/)).toBeVisible({ timeout: 15_000 });
    console.log("  [Agent] 重名提示已出现");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    await page.waitForTimeout(300);

    // 2) 非法 temperature（用另一个名字，避开重名）
    dialog = await openAgentForm();
    await page.getByLabel("名称", { exact: true }).fill(`${agentId}-t`);
    await page.getByLabel(/agentId/).fill(`${agentId}-t`);
    await page.getByLabel("系统提示词").fill("你是助手。");
    await page.getByLabel("模型").selectOption(String(model.data.id));
    await page.getByLabel("temperature").fill("3");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/temperature 必须在 0~2 之间/)).toBeVisible({ timeout: 15_000 });
    console.log("  [Agent] 非法 temperature 提示已出现");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    await page.waitForTimeout(300);

    // 3) 非法 maxTokens
    dialog = await openAgentForm();
    await page.getByLabel("名称", { exact: true }).fill(`${agentId}-m`);
    await page.getByLabel(/agentId/).fill(`${agentId}-m`);
    await page.getByLabel("系统提示词").fill("你是助手。");
    await page.getByLabel("模型").selectOption(String(model.data.id));
    await page.getByLabel("最大输出 tokens（max_tokens）").fill("0");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/maxTokens 必须为正整数/)).toBeVisible({ timeout: 15_000 });
    console.log("  [Agent] 非法 maxTokens 提示已出现");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
  });

  test("模型页：非法货币被拒", async ({ page }) => {
    const stamp = Date.now();
    const dialog = await openModelForm(page, `e2e-err-cur-${stamp}`);
    await page.getByLabel("价格货币").selectOption("USD").catch(async () => {
      // 下拉里没有 EUR 选项时，用接口直接验（前端限制了可选项，后端仍会校验）
    });
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    // 前端下拉只给 CNY/USD，所以这一条从接口侧验证后端仍然会拦
    const res = await page.request.post(`${API}/api/models`, {
      headers,
      data: { providerId, modelId: `e2e-err-cur2-${stamp}`, priceCurrency: "EUR", status: "ENABLED" },
    });
    const body = await res.json();
    console.log("  [货币] 接口返回:", body.code, body.msg);
    expect(body.msg).toContain("价格货币只支持 CNY 或 USD");
  });

  test("技能页：名称空 / 说明空 / 重名各自提示", async ({ page }) => {
    const stamp = Date.now();
    const skillName = `e2e-err-skill-${stamp}`;
    const openSkillForm = async () => {
      await page.goto("/admin/skills");
      await page.getByRole("button", { name: "新建", exact: true }).click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toBeVisible({ timeout: 15_000 });
      return dialog;
    };
    // 1) 名称空 → 前端先拦
    const dialog = await openSkillForm();
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText("名称不能为空")).toBeVisible({ timeout: 10_000 });
    console.log("  [技能] 名称空提示已出现");
    // 2) 说明空 → 前端先拦（平台强制要求说明非空）
    await page.locator('input[placeholder="reply-with-ok"]').fill(skillName);
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/说明不能为空/)).toBeVisible({ timeout: 10_000 });
    console.log("  [技能] 说明空提示已出现");
    // 3) 重名 → 后端拦
    const created = await (
      await page.request.post(`${API}/api/skills`, {
        headers,
        data: { name: skillName, description: "e2e", content: "正文", status: "ENABLED" },
      })
    ).json();
    createdSkills.push(created.data.id as number);
    await page.getByTestId("skill-description").fill("e2e 说明");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/Skill 名称已存在/)).toBeVisible({ timeout: 15_000 });
    console.log("  [技能] 重名提示已出现");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
  });

  test("技能页：被 Agent 绑定时不允许删除", async ({ page }) => {
    const stamp = Date.now();
    const skillName = `e2e-err-boundskill-${stamp}`;
    const skill = await (
      await page.request.post(`${API}/api/skills`, {
        headers,
        data: { name: skillName, description: "e2e", content: "正文", status: "ENABLED" },
      })
    ).json();
    createdSkills.push(skill.data.id as number);
    const model = await (
      await page.request.post(`${API}/api/models`, {
        headers,
        data: { providerId, modelId: `e2e-err-bmodel-${stamp}`, status: "ENABLED" },
      })
    ).json();
    const agent = await (
      await page.request.post(`${API}/api/agents`, {
        headers,
        data: {
          name: `e2e-err-boundagent-${stamp}`,
          agentId: `e2e-err-boundagent-${stamp}`,
          sysPrompt: "你是助手。",
          modelId: model.data.id,
          permissionMode: "ALWAYS_ALLOW",
          maxIterations: 2,
          status: "ENABLED",
          skillIds: [skill.data.id],
          mcpServerIds: [],
        },
      })
    ).json();
    createdAgents.push(agent.data.id as number);

    await page.goto("/admin/skills");
    page.on("dialog", (d) => void d.accept());
    const row = page.locator("tr", { hasText: skillName });
    await expect(row).toBeVisible({ timeout: 20_000 });
    await row.getByTitle("删除").click();
    await expect(page.getByText(/该 Skill 被 \d+ 个 Agent 绑定，无法删除/)).toBeVisible({ timeout: 20_000 });
    console.log("  [技能] 被绑定时删除被拦并提示");
  });

  test("MCP 页：STDIO 缺 command / 重名 / 被绑定不允许删除", async ({ page }) => {
    const stamp = Date.now();
    const mcpName = `e2e-err-mcp-${stamp}`;
    const openMcpForm = async () => {
      await page.goto("/admin/mcp");
      await page.getByRole("button", { name: "新建", exact: true }).click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toBeVisible({ timeout: 15_000 });
      return dialog;
    };
    // 1) STDIO 缺 command
    const dialog = await openMcpForm();
    await dialog.getByLabel("名称").fill(mcpName);
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/STDIO 传输必须提供 command/)).toBeVisible({ timeout: 15_000 });
    console.log("  [MCP] STDIO 缺 command 提示已出现");
    // 2) 重名
    const created = await (
      await page.request.post(`${API}/api/mcp-servers`, {
        headers,
        data: { name: mcpName, transport: "STDIO", command: "cmd", status: "ENABLED" },
      })
    ).json();
    createdMcps.push(created.data.id as number);
    await dialog.getByLabel("命令").fill("cmd");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByText(/MCP Server 名称已存在/)).toBeVisible({ timeout: 15_000 });
    console.log("  [MCP] 重名提示已出现");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    await page.waitForTimeout(300);
    // 3) 被 Agent 绑定时不允许删除
    const model = await (
      await page.request.post(`${API}/api/models`, {
        headers,
        data: { providerId, modelId: `e2e-err-mm-${stamp}`, status: "ENABLED" },
      })
    ).json();
    const agent = await (
      await page.request.post(`${API}/api/agents`, {
        headers,
        data: {
          name: `e2e-err-magent-${stamp}`,
          agentId: `e2e-err-magent-${stamp}`,
          sysPrompt: "你是助手。",
          modelId: model.data.id,
          permissionMode: "ALWAYS_ALLOW",
          maxIterations: 2,
          status: "ENABLED",
          skillIds: [],
          mcpServerIds: [created.data.id],
        },
      })
    ).json();
    createdAgents.push(agent.data.id as number);

    await page.goto("/admin/mcp");
    page.on("dialog", (d) => void d.accept());
    const row = page.locator("tr", { hasText: mcpName });
    await expect(row).toBeVisible({ timeout: 20_000 });
    await row.getByTitle("删除").click();
    await expect(page.getByText(/该 MCP Server 被 \d+ 个 Agent 绑定，无法删除/)).toBeVisible({
      timeout: 20_000,
    });
    console.log("  [MCP] 被绑定时删除被拦并提示");
  });
});
