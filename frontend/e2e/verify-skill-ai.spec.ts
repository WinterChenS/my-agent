import { expect, test, type APIRequestContext } from "@playwright/test";

const API = "http://localhost:8080";
const STUB = "http://localhost:8099/v1";

/**
 * 用户需求：技能创建可以用 AI —— 拿到工具清单与格式要求，按自然语言生成并自动填入编辑器。
 *
 * **自给自足**（项目原则）：不依赖你维护的真实模型，也不依赖网关是否在线。
 * 这里自己造一个指向本地桩的模型，桩按「模型名含 skill-json」返回一份合法的技能草稿 JSON
 * （见 tools/llm-stub/stub.py 的 STUB_SKILL_JSON 分支）。用完清理。
 */
async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  return (await res.json()).data.token as string;
}

test("用 AI 生成技能并自动填入编辑器", async ({ page, request }) => {
  test.setTimeout(180_000);
  const stamp = Date.now();
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };

  // 造：供应商（指向桩）→ 模型（名字含 skill-json，桩会回技能草稿 JSON）
  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `e2e-ai-prov-${stamp}`,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl: STUB,
        apiKey: "stub-key",
        status: "ENABLED",
      },
    })
  ).json();
  const providerId = provider.data.id as number;

  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: {
        providerId,
        modelId: `stub-skill-json-${stamp}`,
        status: "ENABLED",
      },
    })
  ).json();
  const modelId = model.data.id as number;

  try {
    await page.setViewportSize({ width: 1600, height: 950 });
    await page.goto("/login");
    await page.getByLabel("用户名").fill("admin");
    await page.getByLabel("密码").fill("Admin@123");
    await page.getByRole("button", { name: /登录/ }).click();
    await page.waitForURL(/\/chat/);

    await page.goto("/admin/skills");
    await page.getByRole("button", { name: /新建/ }).click();
    await page.getByTestId("skill-ai-panel").waitFor({ timeout: 20_000 });

    // 面板上应写明交给模型的工具数量（本地 N + MCP M）
    console.log(
      "[① AI 面板]",
      (await page.getByTestId("skill-ai-panel").innerText()).replace(/\s+/g, " ").slice(0, 90),
    );
    await expect(page.getByTestId("skill-ai-panel")).toContainText("本地");

    // 选中我们自己造的模型
    await page.getByTestId("skill-ai-model").selectOption(String(modelId));
    console.log("[① 使用的模型]", await page.getByTestId("skill-ai-model").locator("option:checked").innerText());

    // 输入需求 → 生成
    await page
      .getByTestId("skill-ai-requirement")
      .fill("当用户询问退款进度时，先确认订单号再查退款状态；查不到就让他核对订单号。");
    await page.getByTestId("skill-ai-generate").click();

    // 名称被填上 = 生成成功
    const nameInput = page.locator('input[placeholder="reply-with-ok"]');
    await expect(nameInput).not.toHaveValue("", { timeout: 120_000 });
    const name = await nameInput.inputValue();
    const description = await page.getByTestId("skill-description").inputValue();
    const editorText = await page.locator('[contenteditable="true"]').first().innerText();

    console.log("[② name]", name);
    console.log("[③ description]", description.slice(0, 80));
    console.log("[④ 编辑器正文前 100]", editorText.replace(/\s+/g, " ").slice(0, 100));
    console.log("[⑤ 正文引用了真实工具]", editorText.includes("read_file"));

    expect(name).toContain("stub-generated-skill");
    expect(description.length).toBeGreaterThan(0);
    // 正文应出现在**编辑器**里（这一步曾经因为 MDXEditor 的 markdown 只当初始值而失败）
    expect(editorText.length).toBeGreaterThan(20);
    expect(editorText).toContain("桩生成的技能");
    expect(editorText).toContain("read_file");

    await page.screenshot({ path: "../docs/acceptance/task-32-skill-ai-generate.png" });
    await page.getByRole("button", { name: "取消" }).click();
  } finally {
    // 清理自己造的数据，别给库添垃圾
    await request.delete(`${API}/api/models/${modelId}`, { headers });
    await request.delete(`${API}/api/model-providers/${providerId}`, { headers });
  }
});
