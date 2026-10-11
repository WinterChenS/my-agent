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
  const created = await (
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
    })
  ).json();
  return { agentId, agentDbId: created.data.id as number, headers };
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

test("附件暂存 + 发送时上传：暂存区、右侧栏与预览", async ({ page, request }) => {
  const stamp = Date.now();
  const { agentId, agentDbId, headers } = await createStubAgent(request, stamp);
  const input = await loginAndSelectAgent(page, agentId);

  // 两区都在（Phase 10 / Task 10.4）
  await expect(page.locator('[data-testid="panel-sources"]')).toBeVisible();
  await expect(page.locator('[data-testid="panel-artifacts"]')).toBeVisible();
  await expect(page.locator('[data-testid="panel-sources"]')).toContainText("来源文件");
  await expect(page.locator('[data-testid="panel-artifacts"]')).toContainText("产物");

  // 选文件 → 只进「输入框上方暂存区」，此时右侧栏还没有（D24：暂存期间不写库）
  await page.setInputFiles('[data-testid="file-input"]', {
    name: "e2e-note.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("# e2e note\n", "utf-8"),
  });
  const chips = page.locator('[data-testid="attachment-chip"]');
  await expect(chips).toHaveCount(1);
  await expect(chips.first()).toContainText("e2e-note.md");
  await expect(page.locator('[data-testid="panel-sources-item"]')).toHaveCount(0);

  // 输入框仍可用，带着附件发送
  await input.fill("带着附件发一条");
  await input.press("Enter");

  // 发送后才上传：右侧栏出现、暂存区清空
  const sourceItem = page.locator('[data-testid="panel-sources-item"]');
  await expect(sourceItem).toHaveCount(1, { timeout: 30_000 });
  await expect(sourceItem.first()).toContainText("e2e-note.md");
  await expect(sourceItem.first()).toContainText("数据库");
  await expect(chips).toHaveCount(0);

  // 修复项 1：用户气泡里要能看到自己发的附件（此前只显示文字）
  const bubbleAttachment = page.locator('[data-testid="message-attachment"]');
  await expect(bubbleAttachment).toHaveCount(1);
  await expect(bubbleAttachment.first()).toContainText("e2e-note.md");
  await expect(page.locator('[data-testid="user-text"]').last()).toHaveText("带着附件发一条");

  // 修复项 2：附件清单必须随消息一起发给模型（否则模型只能去列目录猜）
  // 直接查库里的用户消息：模型收到的就是这段内容
  const sessionsResp = await (await request.get(`${API}/api/sessions`, { headers })).json();
  const mine = (sessionsResp.data ?? []).filter(
    (s: { agentId: number }) => String(s.agentId) === String(agentDbId),
  );
  expect(mine.length, "应已创建会话").toBeGreaterThan(0);
  const messages = await (
    await request.get(`${API}/api/sessions/${mine[0].id}/messages`, { headers })
  ).json();
  const userMessages = (messages.data ?? []).filter(
    (m: { role: string }) => String(m.role).toUpperCase() === "USER",
  );
  const lastUser = userMessages[userMessages.length - 1];
  expect(String(lastUser?.content)).toContain("[已上传附件]");
  expect(String(lastUser?.content)).toContain("uploads/e2e-note.md");

  // 产物区此时还是空的（Agent 没写文件）
  await expect(page.locator('[data-testid="panel-artifacts-item"]')).toHaveCount(0);

  // 文本类可在线预览
  await sourceItem.first().hover();
  await page.locator('[data-testid="artifact-preview-button"]').first().click();
  await expect(page.locator('[data-testid="artifact-preview"]')).toContainText("e2e note");
  await page.keyboard.press("Escape");

  await page.screenshot({ path: "../docs/acceptance/task-11-attachment-staging.png", fullPage: true });
});

test("附件：多选与追加、移除后库里不留记录、发送时只上传保留的", async ({ page, request }) => {
  const stamp = Date.now();
  const { agentId, agentDbId, headers } = await createStubAgent(request, stamp);
  const input = await loginAndSelectAgent(page, agentId);

  // 一次选两个（A47）
  await page.setInputFiles('[data-testid="file-input"]', [
    { name: "e2e-a.txt", mimeType: "text/plain", buffer: Buffer.from("A\n", "utf-8") },
    { name: "e2e-b.txt", mimeType: "text/plain", buffer: Buffer.from("B\n", "utf-8") },
  ]);
  const chips = page.locator('[data-testid="attachment-chip"]');
  await expect(chips).toHaveCount(2);

  // 分多次追加，不互相覆盖（A48）
  await page.setInputFiles('[data-testid="file-input"]', {
    name: "e2e-c.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("C\n", "utf-8"),
  });
  await expect(chips).toHaveCount(3);
  // 输入框仍可正常使用
  await input.fill("文字仍然可以输入");
  await expect(input).toHaveValue("文字仍然可以输入");

  // 移除其中一条（A49）
  await page.getByLabel("移除附件 e2e-b.txt").click();
  await expect(chips).toHaveCount(2);
  expect((await chips.allTextContents()).join(" ")).not.toContain("e2e-b.txt");

  // 暂存期间库里不应有任何来源文件记录（A50）：本 Agent 的会话要么还没建，要么没有任何 artifact
  const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
  const mine = (sessions.data ?? []).filter(
    (s: { agentId: number }) => String(s.agentId) === String(agentDbId),
  );
  for (const session of mine) {
    const artifacts = await (
      await request.get(`${API}/api/artifacts?sessionId=${session.id}`, { headers })
    ).json();
    expect(artifacts.data, "暂存期间不应产生来源文件记录").toHaveLength(0);
  }

  // 发送 → 只上传保留的两条，被移除的那条不会出现（A51/A52）
  await input.press("Enter");
  const sourceItems = page.locator('[data-testid="panel-sources-item"]');
  await expect(sourceItems).toHaveCount(2, { timeout: 30_000 });
  const texts = await sourceItems.allTextContents();
  expect(texts.join(" ")).toContain("e2e-a.txt");
  expect(texts.join(" ")).toContain("e2e-c.txt");
  expect(texts.join(" ")).not.toContain("e2e-b.txt");
  await expect(chips).toHaveCount(0);
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
