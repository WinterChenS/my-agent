import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { spawn, type ChildProcess } from "node:child_process";
import path from "node:path";

/**
 * Phase 10 浏览器验收：右侧栏（来源文件/产物）、上传、`/` 选 Skill 的不可编辑块。
 *
 * 同样遵守「测试自给自足」：自己造 e2e- 前缀的 provider/model/agent/skill，
 * 不依赖用户维护的数据；afterEach 清理干净。
 */

const API = "http://localhost:8080";
const STUB = "http://localhost:8099/v1";
const PREFIX = "e2e-p10-";
// 产物区用例需要一个「会写文件」的桩：不能复用 8099（那个由 e2e-chat.sh 启动，没有工具），
// 所以自己起一个专属端口的桩，用例内自给自足。
const WRITE_STUB_PORT = 8097;
const WRITE_STUB_FILE = `e2e-artifact-${Date.now()}.md`;
let writeStub: ChildProcess | null = null;

test.beforeAll(() => {
  const repoRoot = path.resolve(process.cwd(), "..");
  writeStub = spawn("python", [path.join(repoRoot, "tools", "llm-stub", "stub.py")], {
    cwd: repoRoot,
    env: {
      ...process.env,
      PORT: String(WRITE_STUB_PORT),
      STUB_REPLY_FROM_MODEL: "1",
      STUB_TOOL_NAME: "write_file",
      STUB_TOOL_ARGS: JSON.stringify({
        path: WRITE_STUB_FILE,
        content: "# artifact written by agent\n",
      }),
    },
    stdio: "ignore",
  });
});

test.afterAll(() => {
  writeStub?.kill();
});

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  return (await res.json()).data.token as string;
}

async function createStubAgent(request: APIRequestContext, stamp: number, baseUrl = STUB) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const agentId = `${PREFIX}agent-${stamp}`;

  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `${PREFIX}prov-${stamp}`,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl,
        apiKey: "sk-e2e",
        status: "ENABLED",
      },
    })
  ).json();
  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: { providerId: provider.data.id, modelId: `${PREFIX}model-${stamp}`, status: "ENABLED" },
    })
  ).json();
  await request.post(`${API}/api/agents`, {
    headers,
    data: {
      name: agentId,
      agentId,
      sysPrompt: "你是测试助手。",
      modelId: model.data.id,
      permissionMode: "ALWAYS_ALLOW",
      maxIterations: 3,
      status: "ENABLED",
      skillIds: [],
      mcpServerIds: [],
    },
  });
  return { agentId, headers };
}

async function createSkill(request: APIRequestContext, headers: Record<string, string>, stamp: number) {
  const name = `${PREFIX}skill-${stamp}`;
  await request.post(`${API}/api/skills`, {
    headers,
    data: { name, description: "e2e 用例造的技能", content: "# e2e", status: "ENABLED" },
  });
  return name;
}

async function apiCleanup(request: APIRequestContext) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const del = async (path: string, id: number) =>
    request.delete(`${API}${path}/${id}`, { headers, failOnStatusCode: false });

  const providers = await (
    await request.get(`${API}/api/model-providers?current=1&size=500`, { headers })
  ).json();
  const providerIds = new Set<number>(
    (providers.data?.records ?? [])
      .filter((p: { name: string }) => String(p.name).startsWith(PREFIX))
      .map((p: { id: number }) => p.id),
  );

  const agents = await (await request.get(`${API}/api/agents?current=1&size=500`, { headers })).json();
  const agentIds = new Set<number>();
  for (const a of agents.data?.records ?? []) {
    if (String(a.agentId).startsWith(PREFIX)) {
      agentIds.add(a.id);
      await del("/api/agents", a.id);
    }
  }

  const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
  for (const s of sessions.data ?? []) {
    if (agentIds.has(Number(s.agentId))) await del("/api/sessions", s.id);
  }

  const models = await (await request.get(`${API}/api/models?current=1&size=500`, { headers })).json();
  for (const m of models.data?.records ?? []) {
    if (String(m.modelId).startsWith(PREFIX) || providerIds.has(Number(m.providerId))) {
      await del("/api/models", m.id);
    }
  }
  for (const id of providerIds) await del("/api/model-providers", id);

  const skills = await (await request.get(`${API}/api/skills?current=1&size=500`, { headers })).json();
  for (const s of skills.data?.records ?? []) {
    if (String(s.name).startsWith(PREFIX)) await del("/api/skills", s.id);
  }
}

test.afterEach(async ({ request }) => {
  await apiCleanup(request);
});

async function loginAndSelectAgent(page: Page, agentName: string) {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

  await page.getByTitle("新建会话").click();
  const agentSelect = page.locator("select");
  await expect(agentSelect).toContainText(agentName, { timeout: 20_000 });
  await agentSelect.selectOption({ label: agentName });
  return page.getByPlaceholder("输入消息，回车发送");
}

test("右侧栏：来源文件与产物两区可见，上传后出现在来源文件区", async ({ page, request }) => {
  const stamp = Date.now();
  const { agentId } = await createStubAgent(request, stamp);
  await loginAndSelectAgent(page, agentId);

  // 两区都在（Phase 10 / Task 10.4）
  await expect(page.locator('[data-testid="panel-sources"]')).toBeVisible();
  await expect(page.locator('[data-testid="panel-artifacts"]')).toBeVisible();
  await expect(page.locator('[data-testid="panel-sources"]')).toContainText("来源文件");
  await expect(page.locator('[data-testid="panel-artifacts"]')).toContainText("产物");

  // 上传一个文本文件 → 来源文件区出现该文件（Task 10.2）
  await page.setInputFiles('[data-testid="file-input"]', {
    name: "e2e-note.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("# e2e note\n", "utf-8"),
  });
  const sourceItem = page.locator('[data-testid="panel-sources-item"]');
  await expect(sourceItem).toHaveCount(1, { timeout: 30_000 });
  await expect(sourceItem.first()).toContainText("e2e-note.md");
  // 文本类入库（D18）→ 右侧栏标注存储位置
  await expect(sourceItem.first()).toContainText("数据库");

  // 产物区此时还是空的（本轮没跑，Agent 没写文件）
  await expect(page.locator('[data-testid="panel-artifacts-item"]')).toHaveCount(0);

  // 文本类可在线预览（10.4 门禁）
  await sourceItem.first().hover();
  await page.locator('[data-testid="artifact-preview-button"]').first().click();
  await expect(page.locator('[data-testid="artifact-preview"]')).toContainText("e2e note");
  await page.keyboard.press("Escape");

  await page.screenshot({ path: "../docs/acceptance/task-10-panel-upload.png", fullPage: true });
});

test("产物区：Agent 写出的文件在 run 结束后自动出现", async ({ page, request }) => {
  const stamp = Date.now();
  const { agentId } = await createStubAgent(request, stamp, `http://localhost:${WRITE_STUB_PORT}/v1`);
  const input = await loginAndSelectAgent(page, agentId);

  await input.fill("帮我写一个文件");
  await input.press("Enter");

  // run 结束后才登记产物（D22 自动登记）
  const artifactItem = page.locator('[data-testid="panel-artifacts-item"]');
  await expect(artifactItem).toHaveCount(1, { timeout: 90_000 });
  await expect(artifactItem.first()).toContainText(WRITE_STUB_FILE);
  // 框架内部状态文件不能混进来
  await expect(artifactItem.first()).not.toContainText("sessions");

  await page.screenshot({ path: "../docs/acceptance/task-10-artifact-area.png", fullPage: true });
});

test("`/` 选技能：变成不可编辑的块、可删除、不重复", async ({ page, request }) => {
  const stamp = Date.now();
  const { agentId, headers } = await createStubAgent(request, stamp);
  const skillName = await createSkill(request, headers, stamp);
  const input = await loginAndSelectAgent(page, agentId);

  // 输入 `/` 弹出菜单
  await input.fill("/");
  await expect(page.locator('[data-testid="skill-menu"]')).toBeVisible({ timeout: 20_000 });
  const option = page.locator('[data-testid="skill-option"]', { hasText: skillName });
  await expect(option).toBeVisible({ timeout: 20_000 });
  await option.click();

  // 选中后：输入框清空，技能变成独立的块（Task 10.5）
  await expect(input).toHaveValue("");
  const block = page.locator('[data-testid="skill-block"]');
  await expect(block).toHaveCount(1);
  await expect(block.first()).toContainText(skillName);

  // 块是不可编辑的：不是输入框，也不可编辑
  expect(await block.first().locator("input, textarea").count()).toBe(0);
  expect(await block.first().evaluate((el) => el.isContentEditable)).toBe(false);

  // 再输入 `/`：已选中的技能不再出现在候选里（去重）
  await input.fill("/");
  await expect(page.locator('[data-testid="skill-menu"]')).toBeVisible();
  await expect(page.locator('[data-testid="skill-option"]', { hasText: skillName })).toHaveCount(0);
  await input.press("Escape");

  // 带着技能块发消息：run 正常完成，块仍在（会话级技能集，D21）
  await input.fill("带技能发一条");
  await input.press("Enter");
  await expect(page.locator('[data-testid="user-text"]').last()).toHaveText("带技能发一条");
  await expect(page.locator('[data-testid="assistant-text"]').last()).toContainText("stub-ok", {
    timeout: 60_000,
  });
  await expect(page.locator('[data-testid="skill-block"]')).toHaveCount(1);

  await page.screenshot({ path: "../docs/acceptance/task-10-skill-block.png", fullPage: true });

  // 删除块
  await page.getByLabel(`删除技能 ${skillName}`).click();
  await expect(page.locator('[data-testid="skill-block"]')).toHaveCount(0);
});
